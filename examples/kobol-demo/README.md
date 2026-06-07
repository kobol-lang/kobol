# Kobol Demo

Full-featured example demonstrating Kobol's core capabilities:

| Module | Features |
|--------|---------|
| `db/db-module.kbl` | JDBC (H2), RECORD, VALIDATE multi-constraint, LOG multi-KV, TEXT SENSITIVE |
| `service/concurrency-module.kbl` | CONCURRENT (virtual threads), LIST, ADD, FOR EACH, PARALLEL FOR EACH |
| `crypto/crypto-service.kbl` | Java interop (`CALL LocalDate.now`), AES encryption, SHA-256/MD5, Base64 |
| `interop/kotlin-interop.kbl` | Kotlin interop — `@Metadata` nullable-return warning `W237` (F15) against a real Kotlin dep on the `lib/` classpath |
| `Main.kbl` | Multi-module imports, CONFIG, REST server, RESPOND WITH body, path params |

## Prerequisites

- **Java 21+** on PATH
- Kobol compiler on PATH, or the project built with `gradlew :compiler:installDist` (auto-detected)

## Build

```bat
kobol build
```
or
```bat
run.bat --build
```

H2 is downloaded from Maven Central automatically on first build (`~/.kobol/cache/`).

## Run

```bat
kobol run
```
or
```bat
run.bat
```

Starts the product catalog API on **http://localhost:8080**.  
Override port: `set PORT=9090 & kobol run`

## Test

```bat
kobol test
```
or
```bat
run.bat --test
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/ping` | Health check — returns `pong` |
| `GET` | `/status` | Version info JSON |
| `POST` | `/echo` | Echo request body as JSON |
| `GET` | `/hello/{name}` | Greeting with path parameter |

### curl examples

```bash
# Health check
curl http://localhost:8080/ping

# Status
curl http://localhost:8080/status

# Echo JSON body
curl -X POST http://localhost:8080/echo \
     -H "Content-Type: application/json" \
     -d '{"message": "hello from Kobol"}'

# Path parameter
curl http://localhost:8080/hello/World
```

### PowerShell examples

```powershell
# Health check
Invoke-RestMethod http://localhost:8080/ping

# Echo JSON
Invoke-RestMethod http://localhost:8080/echo `
    -Method POST `
    -Body '{"message":"hello"}' `
    -ContentType "application/json"

# Path parameter
Invoke-RestMethod http://localhost:8080/hello/World
```

## Source layout

```
src/
  main/
    Main.kbl                         Entry point — CONFIG, REST server
    db/db-module.kbl                 MODULE demo.db
    service/concurrency-module.kbl   MODULE demo.concurrency
    crypto/crypto-service.kbl        MODULE demo.crypto
  test/
    DemoTests.kbl                    TEST blocks + TABLE tests
kobol.toml                           Project descriptor (dependencies, port)
run.bat                              Windows build/run/test script
```

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | REST server port |
| `DB_URL` | `jdbc:h2:mem:kobol_demo;DB_CLOSE_DELAY=-1` | JDBC connection URL |
