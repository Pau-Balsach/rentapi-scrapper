package com.rentapi.rentapiscraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String CARPETA_DEFAULT = "C:\\tools\\rentapi";

    public static void main(String[] args) throws Exception {

        String carpeta = args.length > 0 ? args[0] : CARPETA_DEFAULT;

        log.info("=== RentAPI Scraper ===");
        log.info("Carpeta de trabajo: {}", carpeta);

        File dir = new File(carpeta);
        if (!dir.exists() || !dir.isDirectory()) {
            log.error("La carpeta no existe o no es un directorio: {}", carpeta);
            return;
        }

        List<File> habitaclia = ficherosPorPrefijo(dir, "habitaclia-");
        List<File> idealista  = ficherosPorPrefijo(dir, "idealista-");
        List<File> fotocasa   = ficherosPorPrefijo(dir, "fotocasa-");

        log.info("Ficheros Habitaclia: {}", habitaclia.size());
        habitaclia.forEach(f -> log.info("  · {}", f.getName()));
        log.info("Ficheros Idealista:  {}", idealista.size());
        idealista.forEach(f -> log.info("  · {}", f.getName()));
        log.info("Ficheros Fotocasa:   {}", fotocasa.size());
        fotocasa.forEach(f -> log.info("  · {}", f.getName()));

        if (habitaclia.isEmpty() && idealista.isEmpty() && fotocasa.isEmpty()) {
            log.warn("No se encontraron ficheros en: {}", carpeta);
            return;
        }

        SupabaseClient db = new SupabaseClient();
        db.connect();

        int ok = 0, error = 0;

        try {
            // ── Habitaclia ────────────────────────────────────────────────────
            if (!habitaclia.isEmpty()) {
                log.info("");
                log.info("████ HABITACLIA ({} ficheros) ████", habitaclia.size());
                HabitacliaScraper habitacliaScraper = new HabitacliaScraper(db);
                for (File f : habitaclia) {
                    String slug = extraerSlug(f.getName(), "habitaclia-");
                    log.info("");
                    log.info("━━━ {} (habitaclia) ━━━", slug);
                    try { habitacliaScraper.scrapeDesdeArchivoLocal(f.getAbsolutePath(), slug); ok++; }
                    catch (Exception e) { log.error("Error {}: {}", slug, e.getMessage()); error++; }
                }
            }

            // ── Idealista ─────────────────────────────────────────────────────
            if (!idealista.isEmpty()) {
                log.info("");
                log.info("████ IDEALISTA ({} ficheros) ████", idealista.size());
                IdealistaScraper idealistaScraper = new IdealistaScraper(db);
                for (File f : idealista) {
                    String slug = extraerSlug(f.getName(), "idealista-");
                    log.info("");
                    log.info("━━━ {} (idealista) ━━━", slug);
                    try { idealistaScraper.scrapeDesdeArchivoLocal(f.getAbsolutePath(), slug); ok++; }
                    catch (Exception e) { log.error("Error {}: {}", slug, e.getMessage()); error++; }
                }
            }

            // ── Fotocasa ──────────────────────────────────────────────────────
            if (!fotocasa.isEmpty()) {
                log.info("");
                log.info("████ FOTOCASA ({} ficheros) ████", fotocasa.size());
                FotocasaScraper fotocasaScraper = new FotocasaScraper(db);
                for (File f : fotocasa) {
                    String slug = extraerSlug(f.getName(), "fotocasa-");
                    log.info("");
                    log.info("━━━ {} (fotocasa) ━━━", slug);
                    try { fotocasaScraper.scrapeDesdeArchivoLocal(f.getAbsolutePath(), slug); ok++; }
                    catch (Exception e) { log.error("Error {}: {}", slug, e.getMessage()); error++; }
                }
            }

            // ── Estadísticas ──────────────────────────────────────────────────
            log.info("");
            log.info("████ ESTADÍSTICAS ████");
            try {
                new StatsCalculator(db.getConnection()).calcularMesActual();
            } catch (Exception e) {
                log.error("Error calculando estadísticas: {}", e.getMessage());
            }

        } finally {
            db.close();
        }

        log.info("");
        log.info("=== Scraping completado ===");
        log.info("Total procesados: {} OK, {} con error", ok, error);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static List<File> ficherosPorPrefijo(File dir, String prefijo) {
        File[] ficheros = dir.listFiles((d, name) ->
                name.startsWith(prefijo) && name.endsWith(".html")
        );
        if (ficheros == null) return List.of();
        List<File> lista = Arrays.asList(ficheros);
        lista.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return lista;
    }

    private static String extraerSlug(String nombre, String prefijo) {
        return nombre.replace(prefijo, "").replace(".html", "");
    }
}