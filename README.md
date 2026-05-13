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

## 打包 Windows 客户端

```powershell
pwsh .\scripts\package-client.ps1
```

打包完成后会输出两个产物：

```text
lightnote-client\target\dist\<时间戳>\LightNote
lightnote-client\target\dist\<时间戳>\LightNote-windows-x64-portable.zip
```

其中目录版入口为：

```text
lightnote-client\target\dist\<时间戳>\LightNote\LightNote.cmd
```

如果只想重新生成包体、不重复执行 Maven 构建，可以使用：

```powershell
pwsh .\scripts\package-client.ps1 -SkipBuild
```

如果你想单独走 `jpackage` 的 Windows 启动器链路，也可以执行：

```powershell
pwsh .\scripts\package-client-jpackage.ps1
```

如果本机 `jpackage` 打印 `ResourceEditor` 或留下 `RCX*.tmp`，一般是 Windows 启动器资源写入时的访问拒绝；优先使用 `package-client.ps1` 生成的便携包。
当前机器上 `jpackage` 生成的 `LightNote.exe` 可能会直接闪退，遇到这种情况请使用同目录下的 `LightNote-debug.cmd` 查看错误，或改用稳定便携包入口 `LightNote.cmd`。

## 提交和 VPS 部署

本地一键提交并推送到 VPS Git 仓库：

```powershell
pwsh .\scripts\git-push-vps.ps1 -Message "chore: update lightnote"
```

VPS 上一键构建并部署服务端：

```bash
cd /git-workspace/lightnote-dev
bash scripts/vps-deploy-server.sh
```

详细说明见 [docs/vps-deploy-readme.md](docs/vps-deploy-readme.md)，完整文档索引见 [docs/README.md](docs/README.md)。

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
.
├── lightnote-server/   Spring Boot 服务端
├── lightnote-client/   JavaFX Windows 桌面客户端
├── docs/               设计、接口、数据库、任务和部署文档
├── docker/             Docker Compose 示例
├── scripts/            构建、启动、打包、提交和部署脚本
├── icon.png            应用图标源文件
├── README.md           项目总入口
└── *.md / *.docx       早期设计资料和交付说明
```

文档索引和各文档作用见 [docs/README.md](docs/README.md)。
