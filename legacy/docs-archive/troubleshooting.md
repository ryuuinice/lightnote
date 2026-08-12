# LightNote 故障排查

本文档记录开发、打包、部署和同步时最常见的问题。

## Windows 客户端打包

### jpackage 生成的 LightNote.exe 闪退

现象：

```text
LightNote.exe 双击后立即退出
jpackage 输出 ResourceEditor.cpp / EndUpdateResource / Access denied
目录里残留 RCX*.tmp
```

处理：

```powershell
pwsh .\scripts\package-client.ps1
```

优先使用稳定便携包入口：

```text
lightnote-client\target\dist\<时间戳>\LightNote\LightNote.cmd
```

如果必须排查 `jpackage` 包，使用：

```text
lightnote-client\target\jpackage-dist\<时间戳>\LightNote\LightNote-debug.cmd
```

说明：当前机器的 JDK 17 `jpackage` 在写 Windows launcher 资源时可能触发访问拒绝，属于打包工具链问题，不是业务代码问题。

## VPS 服务端

### 查看容器状态

```bash
cd /git-workspace/lightnote
docker compose ps
docker logs --tail 120 lightnote-server
docker logs --tail 120 lightnote-mysql
```

### 健康检查失败

```bash
curl http://127.0.0.1:8080/api/health
```

如果失败，先看服务端日志：

```bash
docker logs --tail 120 lightnote-server
```

### MySQL 连接失败

检查 `.env`：

```bash
cd /git-workspace/lightnote
cat .env
```

进入数据库：

```bash
docker exec -it lightnote-mysql mysql -uroot -p
```

检查业务库：

```sql
SHOW DATABASES;
USE lightnote;
SHOW TABLES;
```

## 客户端同步

### 登录过期

现象：

```text
手动同步提示需要重新登录
```

处理：按客户端弹窗重新登录。默认开发账号来自 `docs/db.sql`：

```text
admin / admin123
```

### 同步失败但本地可保存

这是预期行为。客户端会保留本地改动，状态灯显示失败原因；网络恢复或重新登录后再手动同步。

## 本地数据和日志

默认本地数据目录：

```text
%USERPROFILE%\.lightnote
```

客户端日志：

```text
%USERPROFILE%\.lightnote\logs\lightnote-client.log
```

临时测试数据目录可以通过启动参数指定：

```powershell
pwsh .\scripts\start-client.ps1 -DataDir "C:\tmp\lightnote-data"
```
