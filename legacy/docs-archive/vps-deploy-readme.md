# LightNote VPS 打包部署

这份说明用于在 VPS 上从 Git 工作区构建并部署 LightNote 服务端。

## 目录约定

```text
/git-workspace/lightnote-dev   源码 Git 仓库
/git-workspace/lightnote       服务端运行目录
```

当前部署脚本默认使用这两个目录。服务端运行目录会保存：

```text
docker-compose.yml
.env
server/lightnote-server.jar
initdb/001-init.sql
data/mysql/
logs/
```

## 本地一键提交并推送

在 Windows 本地项目根目录执行：

```powershell
pwsh .\scripts\git-push-vps.ps1 -Message "chore: update deployment scripts"
```

脚本会执行：

```text
git add -A
git commit -m <Message>
git push vps <当前分支>
```

如果本地没有 `vps` remote，脚本会自动添加：

```text
ssh://root@203.0.113.10/git-workspace/lightnote-dev
```

## VPS 一键打包部署

登录 VPS 后执行：

```bash
cd /git-workspace/lightnote-dev
bash scripts/vps-deploy-server.sh
```

脚本会执行：

```text
git pull
docker run maven:3.9.9-eclipse-temurin-17 mvn package
复制服务端 jar 到 /git-workspace/lightnote/server/
生成或复用 /git-workspace/lightnote/.env
生成 /git-workspace/lightnote/docker-compose.yml
docker compose up -d
检查 /api/health
```

## 常用参数

跳过拉代码：

```bash
SKIP_PULL=true bash scripts/vps-deploy-server.sh
```

跳过测试构建：

```bash
SKIP_TESTS=true bash scripts/vps-deploy-server.sh
```

指定分支：

```bash
BRANCH=master bash scripts/vps-deploy-server.sh
```

指定端口：

```bash
SERVER_PORT=8080 bash scripts/vps-deploy-server.sh
```

指定目录：

```bash
SOURCE_DIR=/git-workspace/lightnote-dev APP_DIR=/git-workspace/lightnote bash scripts/vps-deploy-server.sh
```

## 查看状态

```bash
cd /git-workspace/lightnote
docker compose ps
docker logs --tail 120 lightnote-server
curl http://127.0.0.1:8080/api/health
```

外部访问：

```text
http://203.0.113.10:8080/api/health
```

## MySQL 查询

```bash
cd /git-workspace/lightnote
docker exec -it lightnote-mysql mysql -uroot -p
```

密码在：

```text
/git-workspace/lightnote/.env
```

业务库：

```sql
USE lightnote;
SHOW TABLES;
SELECT COUNT(*) FROM tbl_note;
```

## 注意

- `/git-workspace/lightnote/.env` 只在首次部署时自动生成，后续不会覆盖。
- MySQL 数据保存在 `/git-workspace/lightnote/data/mysql`，不要随意删除。
- 默认账号仍来自 `docs/db.sql`：`admin / admin123`。正式使用前建议修改密码。
