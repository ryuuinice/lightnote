# LightNote Server

LightNote private sync server. The first milestone provides the Spring Boot skeleton, unified API response, global exception handling, security baseline, and health check.

## Requirements

- JDK 17
- Maven 3.9+

## Build

```powershell
mvn package
```

## Run

```powershell
mvn spring-boot:run
```

Or from the repository root:

```powershell
$env:LIGHTNOTE_DB_USERNAME="your-db-user"
$env:LIGHTNOTE_DB_PASSWORD="your-db-password"
pwsh .\scripts\start-server.ps1
```

Health check:

```text
GET http://localhost:8080/api/health
```

Expected response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "UP",
    "service": "lightnote-server"
  }
}
```
