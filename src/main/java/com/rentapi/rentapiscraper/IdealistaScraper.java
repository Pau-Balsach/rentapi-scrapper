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

public class IdealistaScraper {

    private static final Logger log = LoggerFactory.getLogger(IdealistaScraper.class);
    private static final String BASE_URL = "https://www.idealista.com";

    // "1.250€/mes" → extraer solo dígitos
    private static final Pattern PRECIO_PATTERN = Pattern.compile("[\\d]+");
    // "2 hab." → habitaciones
    private static final Pattern HAB_PATTERN    = Pattern.compile("^(\\d+)\\s+hab");
    // "71 m²" → metros
    private static final Pattern M2_PATTERN     = Pattern.compile("(\\d+)\\s*m");
    // "Planta 3ª" / "Bajo" / "Entreplanta"
    private static final Pattern PLANTA_PATTERN = Pattern.compile("(?i)Planta\\s+(\\S+)|^(Bajo|Entreplanta|Sótano)");

    private final SupabaseClient db;

    public IdealistaScraper(SupabaseClient db) {
        this.db = db;
    }

    // ─── Entrada principal ────────────────────────────────────────────────────

    /**
     * Lee un fichero idealista-{provincia}.html (con separadores de página)
     * y procesa todos los pisos que contiene.
     *
     * @param rutaHtml   ruta absoluta al fichero, ej: C:\tools\rentapi\idealista-barcelona.html
     * @param provinciaSlug slug de la provincia, ej: "barcelona"
     */
    public void scrapeDesdeArchivoLocal(String rutaHtml, String provinciaSlug) throws Exception {
        log.info("[Idealista] Leyendo fichero: {}", rutaHtml);

        String contenido = new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(rutaHtml)),
                java.nio.charset.StandardCharsets.UTF_8
        );

        String[] paginas = contenido.split("<!-- PAGINA_SEPARADOR -->");
        log.info("[Idealista] Páginas encontradas: {}", paginas.length);

        int totalPisos = 0;
        for (int i = 0; i < paginas.length; i++) {
            Document doc = Jsoup.parse(paginas[i], BASE_URL);
            List<Piso> pisos = parsePisos(doc, provinciaSlug);
            guardarPisos(pisos);
            totalPisos += pisos.size();
            log.info("[Idealista] Página {}: {} pisos", i + 1, pisos.size());
        }

        log.info("[Idealista] Total pisos procesados: {}", totalPisos);
    }

    // ─── Parser principal ─────────────────────────────────────────────────────

    private List<Piso> parsePisos(Document doc, String provinciaSlug) {
        List<Piso> resultado = new ArrayList<>();

        // Idealista: cada anuncio es <article class="item" data-element-id="...">
        Elements articles = doc.select("article.item[data-element-id]");
        log.info("[Idealista] Artículos encontrados: {}", articles.size());

        for (Element art : articles) {
            try {
                Piso piso = parseArticle(art, provinciaSlug);
                if (piso != null) resultado.add(piso);
            } catch (Exception e) {
                log.warn("[Idealista] Error parseando artículo id={}: {}",
                        art.attr("data-element-id"), e.getMessage());
            }
        }
        return resultado;
    }

    private Piso parseArticle(Element art, String provinciaSlug) throws Exception {

        // ── 1. fuenteId ───────────────────────────────────────────────────────
        String fuenteId = art.attr("data-element-id");
        if (fuenteId.isBlank()) return null;

        // ── 2. Precio ─────────────────────────────────────────────────────────
        Element precioEl = art.selectFirst(".item-price");
        if (precioEl == null) return null;

        // "1.250€/mes" → eliminar separadores de miles → "1250"
        String precioTexto = precioEl.text().replace(".", "").replace(",", "");
        Matcher precioMatcher = PRECIO_PATTERN.matcher(precioTexto);
        if (!precioMatcher.find()) return null;
        BigDecimal precioMes = new BigDecimal(precioMatcher.group());

        // Sanity check: descartar precios absurdos
        if (precioMes.intValue() < 100 || precioMes.intValue() > 50_000) return null;

        // ── 3. Habitaciones, metros y planta ──────────────────────────────────
        Elements detalles = art.select(".item-detail-char .item-detail");
        Short habitaciones = null;
        Integer metros     = null;
        String planta      = null;

        for (Element det : detalles) {
            String txt = det.text().trim();

            Matcher habM = HAB_PATTERN.matcher(txt);
            if (habM.find() && habitaciones == null) {
                habitaciones = Short.parseShort(habM.group(1));
                continue;
            }

            if ((txt.contains("estudio") || txt.contains("studio")) && habitaciones == null) {
                habitaciones = 0;
                continue;
            }

            Matcher m2M = M2_PATTERN.matcher(txt);
            if (m2M.find() && metros == null) {
                metros = Integer.parseInt(m2M.group(1));
                continue;
            }

            Matcher plantaM = PLANTA_PATTERN.matcher(txt);
            if (plantaM.find() && planta == null) {
                planta = txt.length() > 20 ? txt.substring(0, 20) : txt;
            }
        }

        // ── 4. Ciudad y barrio desde el título del anuncio ────────────────────
        // Idealista usa títulos como:
        //   "Piso en Calle del Rosselló, La Dreta de l'Eixample, Barcelona"
        //   "Piso en Calle Mayor, 32, Gràcia, Barcelona"
        Long ciudadId  = null;
        Long barrioId  = null;
        String ciudadSlug = null;

        Element tituloEl = art.selectFirst("a.item-link");
        if (tituloEl != null) {
            String titulo = tituloEl.attr("title");
            String[] partes = titulo.split(",");
            if (partes.length >= 2) {
                // La última parte es la ciudad
                String nombreCiudad = partes[partes.length - 1].trim();
                ciudadSlug = nombreCiudad.toLowerCase()
                        .replaceAll("[àáâãäå]", "a")
                        .replaceAll("[èéêë]", "e")
                        .replaceAll("[ìíîï]", "i")
                        .replaceAll("[òóôõö]", "o")
                        .replaceAll("[ùúûü]", "u")
                        .replaceAll("[ç]", "c")
                        .replaceAll("·l", "l")
                        .replaceAll("[^a-z0-9]", "-")
                        .replaceAll("-+", "-")
                        .replaceAll("^-|-$", "");

                ciudadId = db.obtenerOCrearCiudadId(ciudadSlug, provinciaSlug);

                // La penúltima parte es el barrio
                if (ciudadId != null && partes.length >= 3) {
                    String nombreBarrio = partes[partes.length - 2].trim();
                    barrioId = db.obtenerOCrearBarrioId(nombreBarrio, ciudadId);
                }
            }
        }

        // Si no se pudo resolver la ciudad, descartar el piso
        if (ciudadId == null) {
            log.debug("[Idealista] Ciudad no resuelta para fuenteId={}, título={}",
                    fuenteId, tituloEl != null ? tituloEl.attr("title") : "null");
            return null;
        }

        // ── 5. Amueblado y mascotas desde la descripción ──────────────────────
        String desc = art.select(".item-description").text().toLowerCase();
        Boolean amueblado       = desc.contains("amuebla") ? Boolean.TRUE : null;
        Boolean permiteMascotas = null;
        if (desc.contains("mascota") || desc.contains("animal")) {
            permiteMascotas = !desc.contains("no se aceptan animales") &&
                    !desc.contains("no mascotas") &&
                    !desc.contains("sin mascotas");
        }

        Piso piso = new Piso();
        piso.setFuente("idealista");
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
                if (db.pisoExiste("idealista", piso.getFuenteId())) {
                    // Actualizar precio y fecha de scraping
                    db.actualizarPrecio("idealista", piso.getFuenteId(),
                            piso.getPrecioMes(), LocalDateTime.now());
                    actualizados++;
                } else {
                    db.insertarPiso(piso);
                    nuevos++;
                }
            } catch (Exception e) {
                log.warn("[Idealista] Error guardando fuenteId={}: {}",
                        piso.getFuenteId(), e.getMessage());
                errores++;
            }
        }
        log.info("[Idealista] BD → {} nuevos, {} actualizados, {} errores",
                nuevos, actualizados, errores);
    }
}