# LightNote 发布记录

本文档用于记录每次可交付版本的代码提交、客户端包、服务端部署和验证结果。

## 当前开发版

日期：2026-05-13

当前已知提交：

```text
81efa71 chore: add packaging and vps deployment scripts
```

当前服务端部署：

```text
VPS: 203.0.113.10
源码目录: /git-workspace/lightnote-dev
运行目录: /git-workspace/lightnote
健康检查: http://203.0.113.10:8080/api/health
```

当前客户端打包方式：

```powershell
pwsh .\scripts\package-client.ps1
```

推荐交付入口：

```text
lightnote-client\target\dist\<时间戳>\LightNote\LightNote.cmd
lightnote-client\target\dist\<时间戳>\LightNote-windows-x64-portable.zip
```

## 发布前记录模板

```text
版本：
日期：
Git 提交：
服务端部署目录：
服务端健康检查：
客户端打包产物：
验证结果：
已知问题：
```

## 发布步骤

1. 更新任务和设计文档。
2. 执行客户端测试和打包。
3. 推送代码到 VPS Git 仓库。
4. 在 VPS 执行服务端部署脚本。
5. 执行 smoke 清单。
6. 在本文档追加发布记录。
