# LightNote 文档索引

本文档用于说明项目目录结构、各文档用途，以及后续 review 时应该从哪里开始看。

## 推荐阅读顺序

1. [../README.md](../README.md)：项目总入口，包含环境、构建、运行、打包和部署常用命令。
2. [tasklist.md](tasklist.md)：当前主任务进度和剩余事项。
3. [markdown-migration-tasklist.md](markdown-migration-tasklist.md)：Markdown 迁移专项任务。
4. [design.md](design.md)：当前实现口径下的系统设计。
5. [api.md](api.md)：服务端接口和同步协议。
6. [deploy.md](deploy.md)：部署概览。
7. [vps-deploy-readme.md](vps-deploy-readme.md)：VPS 一键部署操作手册。
8. [db.sql](db.sql)：服务端数据库初始化脚本。
9. [troubleshooting.md](troubleshooting.md)：常见问题排查。
10. [smoke-checklist.md](smoke-checklist.md)：发布前手工验收清单。
11. [release.md](release.md)：发布记录模板和当前发布状态。

## 项目目录

```text
.
├── README.md                         项目总入口和常用命令
├── LightNote_可实现版设计文档.md        早期可实现版设计草案，保留用于追溯需求来源
├── 轻量化云同步笔记系统_设计与Codex实现说明书.docx
│                                      原始设计说明书，保留用于需求背景和交付材料
├── icon.png                          应用图标源文件
├── docker/
│   └── docker-compose.yml            早期 Docker Compose 示例，实际 VPS 部署以脚本生成为准
├── docs/
│   ├── README.md                     文档索引和目录说明
│   ├── api.md                        API、认证和同步协议说明
│   ├── db.sql                        MySQL 初始化脚本和默认开发账号
│   ├── deploy.md                     部署概览和入口说明
│   ├── design.md                     当前系统设计文档
│   ├── markdown-migration-tasklist.md Markdown 迁移专项进度
│   ├── release.md                    发布记录和版本交付信息
│   ├── smoke-checklist.md            发版前手工验收清单
│   ├── tasklist.md                   主线任务进度
│   ├── troubleshooting.md            常见问题排查
│   └── vps-deploy-readme.md          VPS 构建部署操作手册
├── lightnote-client/
│   ├── pom.xml                       Windows 桌面客户端 Maven 配置
│   └── src/                          JavaFX 客户端源码、资源和测试
├── lightnote-server/
│   ├── pom.xml                       Spring Boot 服务端 Maven 配置
│   └── src/                          服务端源码、配置和测试
└── scripts/
    ├── build-all.ps1                 本地构建服务端和客户端
    ├── git-push-vps.ps1              本地一键提交并推送到 VPS Git 仓库
    ├── package-client.ps1            Windows 客户端稳定便携包打包脚本
    ├── package-client-jpackage.ps1   jpackage app-image 打包脚本
    ├── start-client.ps1              本地启动客户端
    ├── start-server.ps1              本地启动服务端
    └── vps-deploy-server.sh          VPS 端构建并部署服务端
```

## 各文档作用

| 文档 | 作用 | 维护时机 |
| --- | --- | --- |
| [../README.md](../README.md) | 项目入口，面向日常开发和运行。 | 构建、启动、打包、部署命令变化时更新。 |
| [api.md](api.md) | 服务端 REST API、认证和同步协议。 | 接口字段、响应结构、同步语义变化时更新。 |
| [db.sql](db.sql) | 服务端 MySQL 初始化脚本。 | 新表、新字段、默认数据变化时更新。 |
| [deploy.md](deploy.md) | 部署概览，指向详细部署手册。 | 部署架构或目标环境变化时更新。 |
| [design.md](design.md) | 产品和技术设计，描述当前系统边界。 | 架构、模块职责、核心流程变化时更新。 |
| [markdown-migration-tasklist.md](markdown-migration-tasklist.md) | Markdown 迁移专项计划和进度。 | Markdown 编辑、预览、迁移、附件策略变化时更新。 |
| [release.md](release.md) | 发布记录、当前可交付版本和发布模板。 | 每次形成可交付版本或服务端部署后更新。 |
| [smoke-checklist.md](smoke-checklist.md) | 手工验收清单。 | 新增关键流程或发版检查项变化时更新。 |
| [tasklist.md](tasklist.md) | 主线任务列表和整体进度。 | 每次完成阶段性功能或调整优先级时更新。 |
| [troubleshooting.md](troubleshooting.md) | 常见问题和排查命令。 | 遇到新的打包、部署、同步、数据库问题时更新。 |
| [vps-deploy-readme.md](vps-deploy-readme.md) | VPS 上打包、部署、排查的具体命令。 | 部署脚本、目录、端口、容器策略变化时更新。 |
| [../LightNote_可实现版设计文档.md](../LightNote_可实现版设计文档.md) | 早期可实现方案草案。 | 通常不再维护，仅作历史参考。 |
| [../轻量化云同步笔记系统_设计与Codex实现说明书.docx](../轻量化云同步笔记系统_设计与Codex实现说明书.docx) | 原始设计说明书。 | 通常不再维护，仅作交付和背景材料。 |

## 文档完整性检查

当前文档已经覆盖：

- 项目定位和常用命令：`README.md`
- 产品和架构设计：`docs/design.md`
- API 和同步协议：`docs/api.md`
- 数据库初始化：`docs/db.sql`
- 主线进度：`docs/tasklist.md`
- Markdown 迁移进度：`docs/markdown-migration-tasklist.md`
- 本地提交推送和 VPS 部署：`docs/vps-deploy-readme.md`
- 常见问题排查：`docs/troubleshooting.md`
- 发布记录：`docs/release.md`
- 发版前手工验收：`docs/smoke-checklist.md`
