# LightNote Deployment

当前 VPS 一键部署脚本和操作说明见 [vps-deploy-readme.md](vps-deploy-readme.md)。

## 当前部署方式

生产部署目标：

- VPS：`203.0.113.10`
- 源码目录：`/git-workspace/lightnote-dev`
- 运行目录：`/git-workspace/lightnote`
- Runtime：Docker Compose
- Server image：`eclipse-temurin:17-jre`
- Database image：`mysql:8.0`
- 对外端口：`8080`

VPS 上执行：

```bash
cd /git-workspace/lightnote-dev
bash scripts/vps-deploy-server.sh
```

部署脚本会在 Docker 容器内执行 Maven 构建，然后复制服务端 Jar 到运行目录并重启服务。

## Development Database

本地开发可以使用独立 MySQL/MariaDB，也可以沿用 VPS Docker MySQL。默认开发数据库配置来自 `lightnote-server/src/main/resources/application.yml`。

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

## Health Check

```bash
curl http://127.0.0.1:8080/api/health
```

外部访问：

```text
http://203.0.113.10:8080/api/health
```
