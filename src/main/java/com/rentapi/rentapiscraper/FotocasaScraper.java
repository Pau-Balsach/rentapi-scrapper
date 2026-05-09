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

public class FotocasaScraper {

    private static final Logger log = LoggerFactory.getLogger(FotocasaScraper.class);
    private static final String BASE_URL = "https://www.fotocasa.es";

    private static final Pattern PRECIO_PATTERN = Pattern.compile("[\\d]+");
    private static final Pattern HAB_PATTERN    = Pattern.compile("(\\d+)\\s+hab");
    private static final Pattern M2_PATTERN     = Pattern.compile("(\\d+)\\s+m²");
    private static final Pattern PLANTA_PATTERN = Pattern.compile("(\\d+)[ªº]\\s*[Pp]lanta|^(Bajo|Entreplanta|Sótano)");
    private static final Pattern ID_PATTERN     = Pattern.compile("/(\\d+)/d");

    private final SupabaseClient db;

    public FotocasaScraper(SupabaseClient db) {
        this.db = db;
    }

    // ─── Entrada principal ────────────────────────────────────────────────────

    /**
     * Lee un fichero fotocasa-{slug}.html y procesa todos los pisos.
     *
     * @param rutaHtml  ruta absoluta al fichero, ej: C:\tools\rentapi\fotocasa-barcelona-capital.html
     * @param ciudadSlug slug de la ciudad, ej: "barcelona-capital"
     */
    public void scrapeDesdeArchivoLocal(String rutaHtml, String ciudadSlug) throws Exception {
        // Fotocasa usa sufijos como "barcelona-capital" — normalizamos al slug de BD
        String slugBD = normalizarSlug(ciudadSlug);

        Long ciudadId = db.obtenerOCrearCiudadId(slugBD, inferirProvincia(slugBD));
        if (ciudadId == null) {
            log.warn("[Fotocasa] No se pudo resolver ciudad para slug: {}", slugBD);
            return;
        }

        log.info("[Fotocasa] Leyendo fichero: {}", rutaHtml);
        String contenido = new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(rutaHtml)),
                java.nio.charset.StandardCharsets.UTF_8
        );

        String[] paginas = contenido.split("<!-- PAGINA_SEPARADOR -->");
        log.info("[Fotocasa] Páginas encontradas: {}", paginas.length);

        int totalPisos = 0;
        for (int i = 0; i < paginas.length; i++) {
            Document doc = Jsoup.parse(paginas[i], BASE_URL);
            List<Piso> pisos = parsePisos(doc, ciudadId, slugBD);
            guardarPisos(pisos);
            totalPisos += pisos.size();
            log.info("[Fotocasa] Página {}: {} pisos", i + 1, pisos.size());
        }

        log.info("[Fotocasa] Total pisos procesados: {}", totalPisos);
    }

    // ─── Parser principal ─────────────────────────────────────────────────────

    private List<Piso> parsePisos(Document doc, Long ciudadId, String ciudadSlug) {
        List<Piso> resultado = new ArrayList<>();
        Elements articles = doc.select("article");
        log.info("[Fotocasa] Artículos encontrados: {}", articles.size());

        for (Element art : articles) {
            // Saltamos skeletons y artículos sin precio
            if (!art.text().contains("€")) continue;
            try {
                Piso piso = parseArticle(art, ciudadId, ciudadSlug);
                if (piso != null) resultado.add(piso);
            } catch (Exception e) {
                log.warn("[Fotocasa] Error parseando artículo: {}", e.getMessage());
            }
        }
        return resultado;
    }

    private Piso parseArticle(Element art, Long ciudadId, String ciudadSlug) throws Exception {

        // ── 1. fuenteId — del href del enlace ─────────────────────────────────
        Element enlace = art.selectFirst("a[href*='/alquiler/']");
        if (enlace == null) return null;

        String href = enlace.attr("href");
        Matcher idMatcher = ID_PATTERN.matcher(href);
        if (!idMatcher.find()) return null;
        String fuenteId = idMatcher.group(1);

        // ── 2. Textos cortos del artículo ─────────────────────────────────────
        List<String> textos = new ArrayList<>();
        for (Element el : art.select("span, p, li")) {
            String t = el.text().trim();
            if (!t.isEmpty() && t.length() < 80) textos.add(t);
        }

        // ── 3. Precio ─────────────────────────────────────────────────────────
        // Fotocasa: "7.300 € /mes" o "1.250€/mes"
        String precioText = textos.stream()
                .filter(t -> t.contains("€") && (t.contains("mes") || t.contains("/")))
                .findFirst().orElse(null);
        if (precioText == null) return null;

        String precioLimpio = precioText.replace(".", "").replace(",", "");
        Matcher precioMatcher = PRECIO_PATTERN.matcher(precioLimpio);
        if (!precioMatcher.find()) return null;
        BigDecimal precioMes = new BigDecimal(precioMatcher.group());
        if (precioMes.intValue() < 100 || precioMes.intValue() > 50_000) return null;

        // ── 4. Habitaciones ───────────────────────────────────────────────────
        Short habitaciones = null;
        for (String t : textos) {
            Matcher m = HAB_PATTERN.matcher(t);
            if (m.find()) { habitaciones = Short.parseShort(m.group(1)); break; }
        }
        if (habitaciones == null) {
            boolean esEstudio = textos.stream()
                    .anyMatch(t -> t.toLowerCase().contains("estudio") || t.toLowerCase().contains("studio"));
            if (esEstudio) habitaciones = 0;
        }

        // ── 5. Metros ─────────────────────────────────────────────────────────
        Integer metros = null;
        for (String t : textos) {
            Matcher m = M2_PATTERN.matcher(t);
            if (m.find()) { metros = Integer.parseInt(m.group(1)); break; }
        }

        // ── 6. Planta ─────────────────────────────────────────────────────────
        String planta = null;
        for (String t : textos) {
            Matcher m = PLANTA_PATTERN.matcher(t);
            if (m.find()) {
                planta = t.replace("·", "").trim();
                if (planta.length() > 20) planta = planta.substring(0, 20);
                break;
            }
        }

        // ── 7. Barrio ─────────────────────────────────────────────────────────
        // Fotocasa incluye el barrio en textos como "Gràcia, Barcelona Capital"
        Long barrioId = null;
        for (String t : textos) {
            if (t.contains(",") && t.length() < 60) {
                String[] partes = t.split(",");
                if (partes.length >= 2) {
                    String posibleBarrio = partes[0].trim();
                    // Descartar textos que no son barrios (precios, características...)
                    if (!posibleBarrio.contains("€") && !posibleBarrio.matches(".*\\d+.*")) {
                        barrioId = db.obtenerOCrearBarrioId(posibleBarrio, ciudadId);
                        if (barrioId != null) break;
                    }
                }
            }
        }

        // ── 8. Amueblado y mascotas — del href ────────────────────────────────
        Boolean amueblado = null;
        if (href.contains("no-amueblado")) amueblado = false;
        else if (href.contains("amueblado")) amueblado = true;

        Boolean permiteMascotas = null;
        if (href.contains("se-aceptan-mascotas")) permiteMascotas = true;

        Piso piso = new Piso();
        piso.setFuente("fotocasa");
        piso.setFuenteId(fuenteId);
        piso.setCiudadId(ciudadId);
        piso.setCiudadSlug(ciudadSlug);
        piso.setBarrioId(barrioId);
        piso.setPrecioMes(precioMes);
        piso.setMetrosCuadrados(metros);
        piso.setHabitaciones(habitaciones);
        piso.setPlanta(planta);
        piso.setAmueblado(amueblado);
        piso.setPermiteMascotas(permiteMascotas);
        piso.setActivo(true);
        piso.setFechaScraping(LocalDateTime.now());
        return piso;
    }

    // ─── Guardar en BD ────────────────────────────────────────────────────────

    private void guardarPisos(List<Piso> pisos) {
        int nuevos = 0, actualizados = 0, errores = 0;
        for (Piso piso : pisos) {
            try {
                if (db.pisoExiste("fotocasa", piso.getFuenteId())) {
                    db.actualizarPrecio("fotocasa", piso.getFuenteId(),
                            piso.getPrecioMes(), LocalDateTime.now());
                    actualizados++;
                } else {
                    db.insertarPiso(piso);
                    nuevos++;
                }
            } catch (Exception e) {
                log.warn("[Fotocasa] Error guardando fuenteId={}: {}",
                        piso.getFuenteId(), e.getMessage());
                errores++;
            }
        }
        log.info("[Fotocasa] BD → {} nuevos, {} actualizados, {} errores",
                nuevos, actualizados, errores);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Normaliza el slug de Fotocasa al slug de la BD.
     * "barcelona-capital" → "barcelona"
     * "girona-capital"    → "girona"
     * "hospitalet-de-llobregat" → "hospitalet-de-llobregat"  (sin cambios)
     */
    private String normalizarSlug(String slugFotocasa) {
        return slugFotocasa.replace("-capital", "");
    }

    /**
     * Infiere la provincia desde el slug de ciudad para resolver provincia_id.
     * Mapeo básico de las ciudades catalanas más conocidas.
     */
    private String inferirProvincia(String slugCiudad) {
        // Ciudades de Girona
        if (slugCiudad.matches(".*(girona|banyoles|blanes|figueres|lloret|olot|" +
                "palafrugell|palamos|roses|sant-feliu|platja|empuriabrava|cadaques|" +
                "besalu|l-escala|la-bisbal|torroella|ripoll|mieres).*")) {
            return "girona";
        }
        // Ciudades de Lleida
        if (slugCiudad.matches(".*(lleida|balaguer|mollerussa|tarrega|cervera|" +
                "la-seu|tremp|solsona).*")) {
            return "lleida";
        }
        // Ciudades de Tarragona
        if (slugCiudad.matches(".*(tarragona|reus|cambrils|salou|tortosa|valls|" +
                "el-vendrell|vila-seca|amposta|calafell|gandesa|mora|deltebre|" +
                "torredembarra|la-pineda).*")) {
            return "tarragona";
        }
        // Por defecto Barcelona
        return "barcelona";
    }
}
