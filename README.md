# RentAPI Scraper 🕷️

Scraper en Java que extrae anuncios de alquiler de **Habitaclia**, **Idealista** y **Fotocasa**, los normaliza y los almacena en una base de datos PostgreSQL (Supabase). Forma parte del ecosistema [RentAPI Web](https://rentapi-web.vercel.app/).

---

## Cómo funciona

El scraper **no hace peticiones HTTP directas** a los portales. En su lugar, lee ficheros `.html` guardados manualmente desde el navegador (con las cookies reales de sesión), lo que evita bloqueos y CAPTCHAs.

El flujo completo es:

```
Navegador (cookies reales)
        │
        ▼
Guardar página como .html  ──►  C:\tools\rentapi\
        │
        ▼
Main.java detecta los ficheros por prefijo
        │
        ├── habitaclia-{ciudad}.html  →  HabitacliaScraper
        ├── idealista-{provincia}.html →  IdealistaScraper
        └── fotocasa-{ciudad}.html    →  FotocasaScraper
                    │
                    ▼
            Jsoup parsea los artículos
                    │
                    ▼
            SupabaseClient → INSERT / UPDATE pisos
                    │
                    ▼
            StatsCalculator → stats_ciudad_mensual
                              stats_barrio_mensual
```

---

## Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 17+ |
| Parser HTML | Jsoup |
| Base de datos | PostgreSQL vía Supabase |
| Build | Maven |
| CI/CD | GitHub Actions |
| Ejecución local | JAR en Windows |

---

## Estructura del proyecto

```
rentapi-scraper/
├── src/main/java/com/rentapi/rentapiscraper/
│   ├── Main.java               # Punto de entrada, orquesta todos los scrapers
│   ├── FotocasaScraper.java    # Parser de Fotocasa
│   ├── HabitacliaScraper.java  # Parser de Habitaclia
│   ├── IdealistaScraper.java   # Parser de Idealista
│   ├── StatsCalculator.java    # Calcula estadísticas mensuales por ciudad y barrio
│   ├── SupabaseClient.java     # Conexión JDBC y operaciones sobre la BD
│   └── model/
│       └── Piso.java           # Modelo de datos de un anuncio
└── src/main/resources/
    └── config.properties       # Credenciales de BD (no subir al repo)
```

---

## Configuración

Crea el fichero `src/main/resources/config.properties` con tus credenciales de Supabase:

```properties
db.url=jdbc:postgresql://<host>:5432/postgres
db.user=postgres
db.password=tu_password
```

> ⚠️ Este fichero está en `.gitignore` y **nunca debe subirse al repositorio**.  
> En GitHub Actions, las credenciales se gestionan como **Secrets**.

---

## Uso local

### 1. Guardar los HTMLs desde el navegador

Con el navegador abierto y sesión iniciada en cada portal, navega a la página de resultados de alquiler de la ciudad que quieras y guarda la página como `Página web, solo HTML`. Repite para cada página de resultados y concatena los ficheros con el separador:

```
<!-- PAGINA_SEPARADOR -->
```

Nombra los ficheros siguiendo este patrón y colócalos en `C:\tools\rentapi\`:

```
habitaclia-barcelona.html
habitaclia-girona.html
idealista-barcelona.html
fotocasa-barcelona-capital.html
```

### 2. Compilar y ejecutar

```bash
# Compilar
mvn clean package -DskipTests

# Ejecutar (carpeta por defecto: C:\tools\rentapi)
java -jar target/rentapi-scraper.jar

# O con carpeta personalizada
java -jar target/rentapi-scraper.jar C:\mis\htmls
```

---

## GitHub Actions

El workflow se ejecuta automáticamente o de forma manual. Las credenciales de BD se pasan como secrets del repositorio:

```yaml
# .github/workflows/scrape.yml (ejemplo)
env:
  DB_URL: ${{ secrets.DB_URL }}
  DB_USER: ${{ secrets.DB_USER }}
  DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
```

> Los HTMLs para el pipeline de CI se suben manualmente al repositorio o se generan en un paso previo del workflow.

---

## Portales soportados

| Portal | Identificación anuncio | Detección de barrio |
|---|---|---|
| Habitaclia | `data-element-id` en `article` | `.list-item-location` |
| Idealista | `data-element-id` en `article.item` | Desde el título del anuncio |
| Fotocasa | ID extraído del `href` del enlace | Texto con formato "Barrio, Ciudad" |

---

## Base de datos

El scraper escribe en las siguientes tablas de Supabase:

- `pisos` — anuncios individuales (INSERT o UPDATE de precio si ya existe)
- `ciudades` — se crean automáticamente si no existen
- `barrios` — se crean automáticamente si no existen
- `stats_ciudad_mensual` — estadísticas agregadas por ciudad y mes
- `stats_barrio_mensual` — estadísticas agregadas por barrio y mes

---

## Relacionado

- 🌐 **RentAPI Web** — [rentapi-web.vercel.app](https://rentapi-web.vercel.app/) — Frontend que consume estos datos

---

Desarrollado por [Pau Balsach](https://github.com/Pau-Balsach)
