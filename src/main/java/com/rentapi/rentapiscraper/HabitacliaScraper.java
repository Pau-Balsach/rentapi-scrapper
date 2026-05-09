package com.rentapi.rentapiscraper;

import com.rentapi.rentapiscraper.model.Piso;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HabitacliaScraper {

    private static final Logger log = LoggerFactory.getLogger(HabitacliaScraper.class);
    private static final String BASE_URL = "https://www.habitaclia.com";
    private static final Pattern ID_PATTERN = Pattern.compile("-i(\\d+)\\.htm");

    private final SupabaseClient db;

    public HabitacliaScraper(SupabaseClient db) {
        this.db = db;
    }

    // ─── Entrada principal: fichero local ─────────────────────────────────────

    public void scrapeDesdeArchivoLocal(String rutaHtml, String ciudadSlug) throws Exception {
        Long ciudadId = db.obtenerCiudadId(ciudadSlug);
        if (ciudadId == null) {
            log.warn("[Habitaclia] Ciudad no encontrada en BD: {}", ciudadSlug);
            return;
        }

        log.info("[Habitaclia] Leyendo fichero: {}", rutaHtml);
        String contenido = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(rutaHtml)), java.nio.charset.StandardCharsets.UTF_8);

        String[] paginas = contenido.split("<!-- PAGINA_SEPARADOR -->");
        log.info("[Habitaclia] Páginas encontradas: {}", paginas.length);

        int totalPisos = 0;
        for (int i = 0; i < paginas.length; i++) {
            Document doc = Jsoup.parse(paginas[i], BASE_URL);
            List<Piso> pisos = parsePisos(doc, ciudadSlug, ciudadId);
            guardarPisos(pisos);
            totalPisos += pisos.size();
            log.info("[Habitaclia] Página {}: {} pisos", i + 1, pisos.size());
        }

        log.info("[Habitaclia] Total pisos procesados: {}", totalPisos);
    }

    // ─── Parser principal ─────────────────────────────────────────────────────

    private List<Piso> parsePisos(Document doc, String ciudadSlug, Long ciudadId) {
        List<Piso> resultado = new ArrayList<>();
        Elements articles = doc.select("article");
        log.info("[Habitaclia] Artículos encontrados: {}", articles.size());

        for (Element art : articles) {
            try {
                Piso piso = parseArticle(art, ciudadSlug, ciudadId);
                if (piso != null) resultado.add(piso);
            } catch (Exception e) {
                log.warn("[Habitaclia] Error parseando artículo: {}", e.getMessage());
            }
        }
        return resultado;
    }

    private Piso parseArticle(Element art, String ciudadSlug, Long ciudadId) throws Exception {

        // ── 1. fuenteId ───────────────────────────────────────────────────────
        Element enlace = art.selectFirst("a[href]");
        if (enlace == null) return null;

        String href = enlace.attr("href");
        Matcher idMatcher = ID_PATTERN.matcher(href);
        if (!idMatcher.find()) return null;
        String fuenteId = idMatcher.group(1);

        // ── 2. Precio, m², habitaciones ───────────────────────────────────────
        Element notify = art.selectFirst(".js-notify");

        BigDecimal precioMes = null;
        Integer metros = null;
        Short habitaciones = null;

        if (notify != null) {
            String pvp = notify.attr("data-pvp");
            if (!pvp.isBlank()) {
                try { precioMes = new BigDecimal(pvp.trim()); } catch (Exception ignored) {}
            }
            String sup = notify.attr("data-sup");
            if (!sup.isBlank()) {
                try { metros = Integer.parseInt(sup.trim()); } catch (Exception ignored) {}
            }
            String hab = notify.attr("data-hab");
            if (!hab.isBlank()) {
                try { habitaciones = Short.parseShort(hab.trim()); } catch (Exception ignored) {}
            }
        }

        if (precioMes == null) precioMes = parsePrecioFallback(art);
        if (precioMes == null) return null;

        // ── 3. Barrio ─────────────────────────────────────────────────────────
        Long barrioId = null;
        Element locEl = art.selectFirst(".list-item-location span, .list-item-location");
        if (locEl != null) {
            String locText = locEl.text().trim();
            String[] partes = locText.split(" - ");
            if (partes.length >= 2) {
                String nombreBarrio = partes[partes.length - 1].trim();
                barrioId = db.obtenerOCrearBarrioId(nombreBarrio, ciudadId);
            }
        }

        // ── 4. Estudio ────────────────────────────────────────────────────────
        if (habitaciones == null) {
            String textoArt = art.text().toLowerCase();
            if (textoArt.contains("estudio") || textoArt.contains("loft")) {
                habitaciones = 0;
            }
        }

        Piso piso = new Piso();
        piso.setFuente("habitaclia");
        piso.setFuenteId(fuenteId);
        piso.setCiudadId(ciudadId);
        piso.setBarrioId(barrioId);
        piso.setPrecioMes(precioMes);
        piso.setMetrosCuadrados(metros);
        piso.setHabitaciones(habitaciones);
        piso.setActivo(true);
        piso.setFechaScraping(LocalDateTime.now());
        piso.setCiudadSlug(ciudadSlug);
        return piso;
    }

    // ─── Fallback precio ──────────────────────────────────────────────────────

    private BigDecimal parsePrecioFallback(Element art) {
        for (Element el : art.select("span, p, strong, div")) {
            String t = el.text().trim();
            if (t.contains("€") && !t.contains("€/m") && t.length() < 30) {
                String limpio = t.replace(".", "").replace(",", "")
                        .replaceAll("[^\\d]", "");
                if (!limpio.isEmpty()) {
                    try {
                        long valor = Long.parseLong(limpio);
                        if (valor >= 200 && valor <= 30_000) {
                            return new BigDecimal(valor);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    // ─── Guardar en BD ────────────────────────────────────────────────────────

    private void guardarPisos(List<Piso> pisos) {
        int nuevos = 0, actualizados = 0;
        for (Piso piso : pisos) {
            try {
                if (db.pisoExiste("habitaclia", piso.getFuenteId())) {
                    actualizados++;
                } else {
                    db.insertarPiso(piso);
                    nuevos++;
                }
            } catch (Exception e) {
                log.warn("[Habitaclia] Error guardando fuenteId={}: {}", piso.getFuenteId(), e.getMessage());
            }
        }
        log.info("[Habitaclia] BD → {} nuevos, {} actualizados", nuevos, actualizados);
    }
}