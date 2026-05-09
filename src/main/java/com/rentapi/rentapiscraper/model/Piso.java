package com.rentapi.rentapiscraper.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class Piso {

    private String fuente;           // 'habitaclia', 'fotocasa'
    private String fuenteId;         // ID único del anuncio en el portal
    private Long ciudadId;           // FK → ciudades.id
    private Long barrioId;           // FK → barrios.id (puede ser null)
    private BigDecimal precioMes;
    private Integer metrosCuadrados;
    private Short habitaciones;
    private String planta;
    private Boolean amueblado;
    private Boolean permiteMascotas;
    private LocalDateTime fechaScraping;
    private LocalDate fechaPublicacion;
    private Boolean activo = true;

    // Campos auxiliares (no van a BD, se usan para resolver FKs)
    private String ciudadSlug;       // ej: "barcelona"
    private String barrioNombre;     // ej: "Gràcia"

    public String getFuente() { return fuente; }
    public void setFuente(String fuente) { this.fuente = fuente; }

    public String getFuenteId() { return fuenteId; }
    public void setFuenteId(String fuenteId) { this.fuenteId = fuenteId; }

    public Long getCiudadId() { return ciudadId; }
    public void setCiudadId(Long ciudadId) { this.ciudadId = ciudadId; }

    public Long getBarrioId() { return barrioId; }
    public void setBarrioId(Long barrioId) { this.barrioId = barrioId; }

    public BigDecimal getPrecioMes() { return precioMes; }
    public void setPrecioMes(BigDecimal precioMes) { this.precioMes = precioMes; }

    public Integer getMetrosCuadrados() { return metrosCuadrados; }
    public void setMetrosCuadrados(Integer metrosCuadrados) { this.metrosCuadrados = metrosCuadrados; }

    public Short getHabitaciones() { return habitaciones; }
    public void setHabitaciones(Short habitaciones) { this.habitaciones = habitaciones; }

    public String getPlanta() { return planta; }
    public void setPlanta(String planta) { this.planta = planta; }

    public Boolean getAmueblado() { return amueblado; }
    public void setAmueblado(Boolean amueblado) { this.amueblado = amueblado; }

    public Boolean getPermiteMascotas() { return permiteMascotas; }
    public void setPermiteMascotas(Boolean permiteMascotas) { this.permiteMascotas = permiteMascotas; }

    public LocalDateTime getFechaScraping() { return fechaScraping; }
    public void setFechaScraping(LocalDateTime fechaScraping) { this.fechaScraping = fechaScraping; }

    public LocalDate getFechaPublicacion() { return fechaPublicacion; }
    public void setFechaPublicacion(LocalDate fechaPublicacion) { this.fechaPublicacion = fechaPublicacion; }

    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }

    public String getCiudadSlug() { return ciudadSlug; }
    public void setCiudadSlug(String ciudadSlug) { this.ciudadSlug = ciudadSlug; }

    public String getBarrioNombre() { return barrioNombre; }
    public void setBarrioNombre(String barrioNombre) { this.barrioNombre = barrioNombre; }
}