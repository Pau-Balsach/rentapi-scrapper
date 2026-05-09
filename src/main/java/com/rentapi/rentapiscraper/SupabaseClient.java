package com.rentapi.rentapiscraper;

import com.rentapi.rentapiscraper.model.Piso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Properties;

public class SupabaseClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseClient.class);
    private Connection conn;

    public void connect() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("config.properties")) {
            props.load(is);
        }
        String url = props.getProperty("db.url");
        String user = props.getProperty("db.user");
        String password = props.getProperty("db.password");

        conn = DriverManager.getConnection(url, user, password);
        log.info("Conectado a Supabase");
    }

    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.error("Error cerrando conexión", e);
        }
    }

    // Expone la conexión para clases que la necesitan directamente (ej: StatsCalculator)
    public Connection getConnection() {
        return conn;
    }

    // ─── Ciudades ─────────────────────────────────────────────────────────────

    // Devuelve el id de la ciudad por slug (ej: "barcelona")
    public Long obtenerCiudadId(String slug) throws SQLException {
        String sql = "SELECT id FROM ciudades WHERE slug = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("id");
        }
        return null;
    }

    /**
     * Busca la ciudad por slug. Si no existe, la crea con los datos disponibles.
     * El nombre se reconstruye desde el slug (ej: "mora-d-ebre" → "Mora d'Ebre").
     * provincia_id, latitud y longitud se dejan null (columnas nullable).
     *
     * @param slug          slug de la ciudad, ej: "calafell"
     * @param provinciaSlug slug de la provincia para intentar resolver provincia_id
     */
    public Long obtenerOCrearCiudadId(String slug, String provinciaSlug) throws SQLException {
        // Primero intentar encontrarla
        Long id = obtenerCiudadId(slug);
        if (id != null) return id;

        // Reconstruir nombre desde slug
        // "mora-d-ebre" → "Mora d'Ebre"
        // "la-pineda"   → "La Pineda"
        // "calafell"    → "Calafell"
        String nombre = slugToNombre(slug);

        // Intentar resolver provincia_id desde su slug
        Long provinciaId = obtenerProvinciaId(provinciaSlug);

        // Crear la ciudad
        String insertSql = """
            INSERT INTO ciudades (nombre, slug, provincia_id, latitud, longitud, created_at)
            VALUES (?, ?, ?, NULL, NULL, NOW())
            RETURNING id
            """;
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, nombre);
            ps.setString(2, slug);
            setLongOrNull(ps, 3, provinciaId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Long nuevaId = rs.getLong("id");
                log.info("Ciudad creada: {} / {} (provincia_id={}) → id={}",
                        nombre, slug, provinciaId, nuevaId);
                return nuevaId;
            }
        }

        log.error("No se pudo crear la ciudad: {}", slug);
        return null;
    }

    // Busca el id de la provincia por su slug (ej: "barcelona", "tarragona")
    private Long obtenerProvinciaId(String provinciaSlug) {
        if (provinciaSlug == null || provinciaSlug.isBlank()) return null;
        try {
            String sql = "SELECT id FROM provincias WHERE slug = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, provinciaSlug);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getLong("id");
            }
        } catch (SQLException e) {
            // Si la tabla no se llama "provincias", no bloqueamos el flujo
            log.debug("No se pudo resolver provincia_id para slug={}: {}", provinciaSlug, e.getMessage());
        }
        return null;
    }

    /**
     * Convierte un slug en un nombre legible:
     *   "mora-d-ebre"   → "Mora d'Ebre"
     *   "la-pineda"     → "La Pineda"
     *   "l-hospitalet"  → "L'Hospitalet"
     *   "calafell"      → "Calafell"
     */
    private String slugToNombre(String slug) {
        String[] partes = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partes.length; i++) {
            String parte = partes[i];
            if (parte.isBlank()) continue;

            // Artículos que van en minúscula excepto al inicio
            boolean esArticulo = parte.equals("de") || parte.equals("del")
                    || parte.equals("la") || parte.equals("les")
                    || parte.equals("els") || parte.equals("i");

            // "d" y "l" solos → apóstrofe con la siguiente palabra
            if ((parte.equals("d") || parte.equals("l")) && i + 1 < partes.length) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(parte).append("'").append(capitalize(partes[i + 1]));
                i++; // saltar la siguiente
                continue;
            }

            if (sb.length() > 0) sb.append(" ");

            if (esArticulo && i > 0) {
                sb.append(parte); // minúscula
            } else {
                sb.append(capitalize(parte));
            }
        }
        return sb.toString();
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    // ─── Barrios ──────────────────────────────────────────────────────────────

    // Busca el barrio por nombre y ciudad; si no existe, lo crea
    public Long obtenerOCrearBarrioId(String nombre, Long ciudadId) throws SQLException {
        if (nombre == null || nombre.isBlank()) return null;

        String slug = nombre.toLowerCase()
                .replaceAll("[àáâãäå]", "a")
                .replaceAll("[èéêë]", "e")
                .replaceAll("[ìíîï]", "i")
                .replaceAll("[òóôõö]", "o")
                .replaceAll("[ùúûü]", "u")
                .replaceAll("[ç]", "c")
                .replaceAll("[^a-z0-9]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // Buscar existente
        String selectSql = "SELECT id FROM barrios WHERE slug = ? AND ciudad_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, slug);
            ps.setLong(2, ciudadId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("id");
        }

        // Crear nuevo
        String insertSql = "INSERT INTO barrios (nombre, slug, ciudad_id, created_at) " +
                "VALUES (?, ?, ?, NOW()) RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, nombre);
            ps.setString(2, slug);
            ps.setLong(3, ciudadId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Long id = rs.getLong("id");
                log.info("Barrio creado: {} ({})", nombre, id);
                return id;
            }
        }
        return null;
    }

    // ─── Pisos ────────────────────────────────────────────────────────────────

    public boolean pisoExiste(String fuente, String fuenteId) throws SQLException {
        String sql = "SELECT 1 FROM pisos WHERE fuente = ? AND fuente_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fuente);
            ps.setString(2, fuenteId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    public void insertarPiso(Piso p) throws SQLException {
        String sql = """
            INSERT INTO pisos (
                fuente, fuente_id, ciudad_id, barrio_id,
                precio_mes, metros_cuadrados, habitaciones,
                planta, amueblado, permite_mascotas,
                fecha_scraping, fecha_publicacion, activo, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getFuente());
            ps.setString(2, p.getFuenteId());
            setLongOrNull(ps, 3, p.getCiudadId());
            setLongOrNull(ps, 4, p.getBarrioId());
            ps.setBigDecimal(5, p.getPrecioMes());
            setIntOrNull(ps, 6, p.getMetrosCuadrados());
            setShortOrNull(ps, 7, p.getHabitaciones());
            ps.setString(8, p.getPlanta());
            setBoolOrNull(ps, 9, p.getAmueblado());
            setBoolOrNull(ps, 10, p.getPermiteMascotas());
            ps.setObject(11, p.getFechaScraping());
            ps.setObject(12, p.getFechaPublicacion());
            ps.setBoolean(13, Boolean.TRUE.equals(p.getActivo()));
            ps.executeUpdate();
        }
    }

    public void actualizarPrecio(String fuente, String fuenteId,
                                 BigDecimal nuevoPrecio,
                                 LocalDateTime fechaScraping) throws SQLException {
        String sql = """
            UPDATE pisos
            SET precio_mes = ?, fecha_scraping = ?, activo = true
            WHERE fuente = ? AND fuente_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, nuevoPrecio);
            ps.setObject(2, fechaScraping);
            ps.setString(3, fuente);
            ps.setString(4, fuenteId);
            ps.executeUpdate();
        }
    }

    public void marcarInactivo(String fuente, String fuenteId) throws SQLException {
        String sql = "UPDATE pisos SET activo = false WHERE fuente = ? AND fuente_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fuente);
            ps.setString(2, fuenteId);
            ps.executeUpdate();
        }
    }

    // ─── Helpers para nulls ───────────────────────────────────────────────────

    private void setLongOrNull(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v != null) ps.setLong(i, v); else ps.setNull(i, Types.BIGINT);
    }
    private void setIntOrNull(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v != null) ps.setInt(i, v); else ps.setNull(i, Types.INTEGER);
    }
    private void setShortOrNull(PreparedStatement ps, int i, Short v) throws SQLException {
        if (v != null) ps.setShort(i, v); else ps.setNull(i, Types.SMALLINT);
    }
    private void setBoolOrNull(PreparedStatement ps, int i, Boolean v) throws SQLException {
        if (v != null) ps.setBoolean(i, v); else ps.setNull(i, Types.BOOLEAN);
    }
}