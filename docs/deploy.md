# LightNote Deployment

The first server milestone is a local Spring Boot skeleton.

Production deployment target:

- VPS: Ubuntu 24.04 LTS
- Runtime: Docker
- Java image: `eclipse-temurin:17-jre`
- Database: MariaDB 10.5 on `10.10.5.57`, or MySQL 8.0 compatible deployment
- Reverse proxy: Nginx with HTTPS

Detailed Docker Compose deployment will be added after the service API is implemented.

## Development Database

The current development database target is:

```text
Host: 10.10.5.57
Port: 3306
Database: lightnote
Default JDBC URL: jdbc:mariadb://10.10.5.57:3306/lightnote?useUnicode=true&characterEncoding=utf8
```

Set credentials through environment variables before running the server:

```powershell
$env:LIGHTNOTE_DB_USERNAME="lightnote"
$env:LIGHTNOTE_DB_PASSWORD="your-password"
mvn spring-boot:run
```

If the database user has not been created yet, import `docs/db.sql` with an account that can create databases and tables.
