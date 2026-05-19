package com.rentapi.rentapiscraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;

public class StatsCalculator {

    private static final Logger log = LoggerFactory.getLogger(StatsCalculator.class);
    private static final int MIN_MUESTRAS = 5;

    private final Connection conn;

    public StatsCalculator(Connection conn) {
        this.conn = conn;
    }

    // ─── Punto de entrada ─────────────────────────────────────────────────────

    public void calcularMesActual() throws SQLException {
        LocalDate mes = LocalDate.now().withDayOfMonth(1);
        log.info("[Stats] Iniciando cálculo para el mes: {}", mes);
        calcularMes(mes);
        log.info("[Stats] Cálculo completado para el mes: {}", mes);
    }

    public void calcularMes(LocalDate mes) throws SQLException {
        LocalDate hasta = mes.plusMonths(1);
        calcularStatsCiudades(mes, hasta);
        calcularStatsBarrios(mes, hasta);
    }

    // ─── Stats ciudades ───────────────────────────────────────

    private void calcularStatsCiudades(LocalDate mes, LocalDate hasta) throws SQLException {
        log.info("[Stats] Calculando stats de ciudades...");
        String sql = """
            WITH base AS (
                SELECT
                    ciudad_id,
                    habitaciones,
                    precio_mes,
                    CASE WHEN metros_cuadrados > 5 THEN precio_mes / metros_cuadrados END AS precio_m2
                FROM pisos
                WHERE activo = true
                  AND fecha_scraping >= ?
                  AND fecha_scraping < ?
                  AND precio_mes IS NOT NULL
            ),
            tipologias AS (
                SELECT unnest(ARRAY[NULL, 0, 1, 2, 3, 4]::smallint[]) AS tipologia
            ),
            agrupado AS (
                SELECT
                    b.ciudad_id,
                    t.tipologia                                         AS habitaciones,
                    COUNT(*)                                            AS total_muestras,
                    ROUND(AVG(b.precio_mes)::numeric, 2)               AS precio_medio,
                    ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY b.precio_mes)::numeric, 2) AS precio_mediana,
                    MIN(b.precio_mes)                                   AS precio_min,
                    MAX(b.precio_mes)                                   AS precio_max,
                    ROUND(PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY b.precio_mes)::numeric, 2) AS percentil_25,
                    ROUND(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY b.precio_mes)::numeric, 2) AS percentil_75,
                    ROUND(AVG(b.precio_m2)::numeric, 2)                AS precio_medio_m2
                FROM base b
                CROSS JOIN tipologias t
                WHERE (t.tipologia IS NULL OR b.habitaciones = t.tipologia)
                GROUP BY b.ciudad_id, t.tipologia
                HAVING COUNT(*) >= ?
            )
            SELECT * FROM agrupado
            ORDER BY ciudad_id, habitaciones NULLS FIRST
            """;

        int insertados = 0, actualizados = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, mes.atStartOfDay());
            ps.setObject(2, hasta.atStartOfDay());
            ps.setInt(3, MIN_MUESTRAS);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Long   ciudadId     = rs.getLong("ciudad_id");
                Short  habitaciones = rs.getObject("habitaciones") != null
                        ? rs.getShort("habitaciones") : null;
                int    muestras     = rs.getInt("total_muestras");
                BigDecimal medio    = rs.getBigDecimal("precio_medio");
                BigDecimal mediana  = rs.getBigDecimal("precio_mediana");
                BigDecimal min      = rs.getBigDecimal("precio_min");
                BigDecimal max      = rs.getBigDecimal("precio_max");
                BigDecimal p25      = rs.getBigDecimal("percentil_25");
                BigDecimal p75      = rs.getBigDecimal("percentil_75");
                BigDecimal medioM2  = rs.getBigDecimal("precio_medio_m2");

                boolean updated = upsertCiudad(
                        ciudadId, mes, habitaciones,
                        muestras, medio, mediana, min, max, p25, p75, medioM2
                );
                if (updated) actualizados++; else insertados++;
            }
        }

        log.info("[Stats] Ciudades → {} insertados, {} actualizados", insertados, actualizados);
    }

    // ─── Stats barrios ────────────────────────────────────────

    private void calcularStatsBarrios(LocalDate mes, LocalDate hasta) throws SQLException {
        log.info("[Stats] Calculando stats de barrios...");

        String sql = """
            WITH base AS (
                SELECT
                    barrio_id,
                    habitaciones,
                    precio_mes,
                    CASE WHEN metros_cuadrados > 0 THEN precio_mes / metros_cuadrados END AS precio_m2
                FROM pisos
                WHERE activo = true
                  AND fecha_scraping >= ?
                  AND fecha_scraping < ?
                  AND precio_mes IS NOT NULL
                  AND barrio_id IS NOT NULL
            ),
            tipologias AS (
                SELECT unnest(ARRAY[NULL, 0, 1, 2, 3, 4]::smallint[]) AS tipologia
            ),
            agrupado AS (
                SELECT
                    b.barrio_id,
                    t.tipologia                                         AS habitaciones,
                    COUNT(*)                                            AS total_muestras,
                    ROUND(AVG(b.precio_mes)::numeric, 2)               AS precio_medio,
                    ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY b.precio_mes)::numeric, 2) AS precio_mediana,
                    MIN(b.precio_mes)                                   AS precio_min,
                    MAX(b.precio_mes)                                   AS precio_max,
                    ROUND(PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY b.precio_mes)::numeric, 2) AS percentil_25,
                    ROUND(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY b.precio_mes)::numeric, 2) AS percentil_75,
                    ROUND(AVG(b.precio_m2)::numeric, 2)                AS precio_medio_m2
                FROM base b
                CROSS JOIN tipologias t
                WHERE (t.tipologia IS NULL OR b.habitaciones = t.tipologia)
                GROUP BY b.barrio_id, t.tipologia
                HAVING COUNT(*) >= ?
            )
            SELECT * FROM agrupado
            ORDER BY barrio_id, habitaciones NULLS FIRST
            """;

        int insertados = 0, actualizados = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, mes.atStartOfDay());
            ps.setObject(2, hasta.atStartOfDay());
            ps.setInt(3, MIN_MUESTRAS);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Long   barrioId     = rs.getLong("barrio_id");
                Short  habitaciones = rs.getObject("habitaciones") != null
                        ? rs.getShort("habitaciones") : null;
                int    muestras     = rs.getInt("total_muestras");
                BigDecimal medio    = rs.getBigDecimal("precio_medio");
                BigDecimal mediana  = rs.getBigDecimal("precio_mediana");
                BigDecimal min      = rs.getBigDecimal("precio_min");
                BigDecimal max      = rs.getBigDecimal("precio_max");
                BigDecimal p25      = rs.getBigDecimal("percentil_25");
                BigDecimal p75      = rs.getBigDecimal("percentil_75");
                BigDecimal medioM2  = rs.getBigDecimal("precio_medio_m2");

                boolean updated = upsertBarrio(
                        barrioId, mes, habitaciones,
                        muestras, medio, mediana, min, max, p25, p75, medioM2
                );
                if (updated) actualizados++; else insertados++;
            }
        }

        log.info("[Stats] Barrios → {} insertados, {} actualizados", insertados, actualizados);
    }

    // ─── Upsert ciudad ────────────────────────────────────────────────────────

    /** @return true si actualizó, false si insertó */
    private boolean upsertCiudad(Long ciudadId, LocalDate mes, Short habitaciones,
                                 int muestras, BigDecimal medio, BigDecimal mediana,
                                 BigDecimal min, BigDecimal max,
                                 BigDecimal p25, BigDecimal p75,
                                 BigDecimal medioM2) throws SQLException {
        String sql = """
            INSERT INTO stats_ciudad_mensual
                (ciudad_id, mes, habitaciones, total_muestras,
                 precio_medio, precio_mediana, precio_min, precio_max,
                 percentil_25, percentil_75, precio_medio_m2, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (ciudad_id, mes, habitaciones)
            DO UPDATE SET
                total_muestras  = EXCLUDED.total_muestras,
                precio_medio    = EXCLUDED.precio_medio,
                precio_mediana  = EXCLUDED.precio_mediana,
                precio_min      = EXCLUDED.precio_min,
                precio_max      = EXCLUDED.precio_max,
                percentil_25    = EXCLUDED.percentil_25,
                percentil_75    = EXCLUDED.percentil_75,
                precio_medio_m2 = EXCLUDED.precio_medio_m2
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ciudadId);
            ps.setObject(2, mes);
            setShortOrNull(ps, 3, habitaciones);
            ps.setInt(4, muestras);
            ps.setBigDecimal(5, medio);
            ps.setBigDecimal(6, mediana);
            ps.setBigDecimal(7, min);
            ps.setBigDecimal(8, max);
            ps.setBigDecimal(9, p25);
            ps.setBigDecimal(10, p75);
            ps.setBigDecimal(11, medioM2);
            ps.executeUpdate();
        }
        return false; // ON CONFLICT lo gestiona Postgres; simplificamos el conteo
    }

    // ─── Upsert barrio ────────────────────────────────────────────────────────

    private boolean upsertBarrio(Long barrioId, LocalDate mes, Short habitaciones,
                                 int muestras, BigDecimal medio, BigDecimal mediana,
                                 BigDecimal min, BigDecimal max,
                                 BigDecimal p25, BigDecimal p75,
                                 BigDecimal medioM2) throws SQLException {
        String sql = """
            INSERT INTO stats_barrio_mensual
                (barrio_id, mes, habitaciones, total_muestras,
                 precio_medio, precio_mediana, precio_min, precio_max,
                 percentil_25, percentil_75, precio_medio_m2, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (barrio_id, mes, habitaciones)
            DO UPDATE SET
                total_muestras  = EXCLUDED.total_muestras,
                precio_medio    = EXCLUDED.precio_medio,
                precio_mediana  = EXCLUDED.precio_mediana,
                precio_min      = EXCLUDED.precio_min,
                precio_max      = EXCLUDED.precio_max,
                percentil_25    = EXCLUDED.percentil_25,
                percentil_75    = EXCLUDED.percentil_75,
                precio_medio_m2 = EXCLUDED.precio_medio_m2
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, barrioId);
            ps.setObject(2, mes);
            setShortOrNull(ps, 3, habitaciones);
            ps.setInt(4, muestras);
            ps.setBigDecimal(5, medio);
            ps.setBigDecimal(6, mediana);
            ps.setBigDecimal(7, min);
            ps.setBigDecimal(8, max);
            ps.setBigDecimal(9, p25);
            ps.setBigDecimal(10, p75);
            ps.setBigDecimal(11, medioM2);
            ps.executeUpdate();
        }
        return false;
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void setShortOrNull(PreparedStatement ps, int i, Short v) throws SQLException {
        if (v != null) ps.setShort(i, v); else ps.setNull(i, Types.SMALLINT);
    }
}