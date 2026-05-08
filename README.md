# LightNote

LightNote 是一个轻量化、本地优先、支持私有云同步的桌面笔记系统。

当前进度：

- 服务端：Spring Boot + JWT + MariaDB + 笔记 CRUD + 手动同步接口
- 客户端：JavaFX + SQLite + 本地编辑 + 搜索 + 登录 + 手动同步

## 环境

- JDK 17
- Maven 3.9+
- MariaDB/MySQL

当前开发数据库：

```text
Host: 10.10.5.57
Port: 3306
Database: lightnote
```

## 构建

```powershell
pwsh .\scripts\build-all.ps1
```

## 启动服务端

推荐使用环境变量传入数据库账号密码：

```powershell
$env:LIGHTNOTE_DB_USERNAME="你的账号"
$env:LIGHTNOTE_DB_PASSWORD="你的密码"
pwsh .\scripts\start-server.ps1
```

服务端默认地址：

```text
http://localhost:8080
```

健康检查：

```text
GET http://localhost:8080/api/health
```

## 启动客户端

```powershell
pwsh .\scripts\start-client.ps1
```

客户端默认本地数据目录：

```text
%USERPROFILE%\.lightnote\lightnote.db
```

也可以指定临时数据目录：

```powershell
pwsh .\scripts\start-client.ps1 -DataDir "C:\tmp\lightnote-data"
```

## 默认开发账号

```text
username: admin
password: admin123
```

首次进入客户端时，服务端地址填写：

```text
http://localhost:8080
```

## 目录

```text
lightnote-server/   服务端
lightnote-client/   Windows 桌面客户端
docs/               设计、接口、数据库、部署文档
docker/             Docker Compose 示例
scripts/            本地构建和启动脚本
```
