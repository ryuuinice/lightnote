# LightNote Markdown 迁移 Tasklist

更新时间：2026-05-12

## 目标

将当前基于 JavaFX `HTMLEditor` 的富文本正文，分阶段迁移为以 Markdown 为主的正文模型。第一阶段只处理纯文本 Markdown 编辑、预览、保存和同步；第二阶段再扩展图片和自定义附件。

## 范围边界

- 第一阶段保留现有 `content` 字段，语义从 HTML 片段切换为 Markdown 文本。
- 第一阶段不做图片二进制同步，不新增附件表。
- 第一阶段不追求复杂所见即所得，优先做稳定、可同步、可搜索、可预览。
- 第二阶段再引入 `lightnote-asset://asset_uuid` 引用、附件元数据表、文件上传下载和本地缓存。

## 关键里程碑

### M0：迁移前基线

目标：确认当前 HTML 富文本版本可回退、可追踪。

- [x] 提交迁移前 checkpoint。
- [ ] 记录当前本地数据库 schema 版本和同步协议版本。
- [ ] 手工 smoke 当前版本：新建、编辑、同步、冲突副本、分类筛选。
- [ ] 选定 Markdown 渲染方案：Java 库渲染后放入 `WebView`，或前端 JS 渲染。

验收标准：

- Git 历史中有明确的迁移前提交。
- 当前客户端 `mvn test` 通过。
- 有一份可执行的迁移任务清单。

### M1：正文模型切换为 Markdown

目标：让本地保存和同步链路都以 Markdown 文本为事实源。

- [ ] 引入 `content_format` 概念，建议取值 `HTML`、`MARKDOWN`。
- [ ] 本地 SQLite `notes` 增加 `content_format TEXT NOT NULL DEFAULT 'HTML'`。
- [ ] 服务端 `tbl_note` 增加 `content_format VARCHAR(16) NOT NULL DEFAULT 'HTML'`。
- [ ] DTO 增加 `contentFormat` 字段，保持旧客户端兼容默认值。
- [ ] `Note` / `RemoteNote` 模型增加 `contentFormat`。
- [ ] `NoteRepository` 保存、读取、同步应用远端内容时保留格式字段。
- [ ] `NoteService` / `SyncService` 按 `contentFormat` 处理摘要生成。

验收标准：

- 旧 HTML 笔记不会被误当 Markdown。
- 新 Markdown 笔记同步到服务端后再拉回，内容完全一致。
- 客户端和服务端测试通过。

### M2：编辑器 UI 替换

目标：用 Markdown 编辑体验替换 `HTMLEditor`，保留当前三栏产品体验。

- [ ] 将 `HTMLEditor` 替换为 Markdown 输入区，优先使用 `TextArea`。
- [ ] 增加预览区，建议使用 `WebView` 渲染 Markdown HTML。
- [ ] 支持编辑/预览/分屏三种模式，先做最小可用版本。
- [ ] 字数统计改为 Markdown 纯文本统计。
- [ ] 卡片摘要从 Markdown 渲染后的纯文本生成。
- [ ] 搜索继续覆盖标题、摘要、正文。
- [ ] 移除或隐藏 HTML 富文本工具条相关逻辑。

验收标准：

- 连续输入自动保存不丢内容。
- 同步时不重载正在编辑的正文。
- Markdown 标题、列表、代码块、引用、链接能正确预览。
- 不再出现 HTML 标签外露问题。

### M3：HTML 存量迁移策略

目标：处理现有 HTML 笔记，避免用户数据丢失。

- [ ] 启动时保留 HTML 笔记原文，不自动破坏性转换。
- [ ] 增加“转换为 Markdown”能力，先做单篇转换。
- [ ] 提供批量转换入口前，先完成转换预览和确认。
- [ ] HTML 转 Markdown 工具保留正文文本、标题、列表、粗斜体、链接、代码块。
- [ ] 不支持的复杂样式降级为纯文本。
- [ ] 转换后将 `content_format` 设置为 `MARKDOWN`，并标记本地待同步。

验收标准：

- HTML 笔记打开时不会丢内容。
- 单篇转换前后可人工比对。
- 转换失败不会覆盖原正文。

### M4：同步协议收口

目标：Markdown 模型下同步稳定，冲突处理仍然可用。

- [ ] 更新 `SyncNoteRequest`、`SyncChangeNote`、`NoteResponse` 文档。
- [ ] 服务端变化拉取返回 `contentFormat`。
- [ ] 冲突副本保留原格式字段。
- [ ] 服务端摘要生成支持 Markdown 去标记。
- [ ] 补同步测试：Markdown 创建、更新、冲突、拉取、旧 HTML 兼容。
- [ ] 更新 `docs/api.md` 和 `docs/design.md`。

验收标准：

- Markdown 笔记跨端同步不改变内容。
- HTML 旧笔记仍可被拉取和展示。
- 冲突副本标题、正文、格式字段都正确。

### M5：附件阶段设计

目标：为第二阶段图片和自定义附件做清晰设计，但不在第一阶段实现二进制同步。

- [ ] 设计本地 `attachments` 表。
- [ ] 设计服务端 `tbl_attachment` 表。
- [ ] 约定正文引用格式：`![name](lightnote-asset://asset_uuid)`。
- [ ] 定义附件状态：`LOCAL_ONLY`、`DIRTY`、`SYNCED`、`DELETE_PENDING`、`MISSING`。
- [ ] 定义文件存储路径和 hash 去重策略。
- [ ] 设计附件上传、下载、删除 API。
- [ ] 设计附件同步顺序：先元数据，后二进制；先下载缺失资源，再完成预览。

验收标准：

- 有可实现的附件数据模型和接口草案。
- Markdown 第一阶段不阻塞第二阶段扩展。

## 推荐执行顺序

1. 完成 M0，确保当前版本可回退。
2. 做 M1，让模型和协议先能表达 Markdown。
3. 做 M2，用最小编辑器替换 `HTMLEditor`。
4. 做 M3，解决历史 HTML 笔记。
5. 做 M4，把同步、测试和文档收口。
6. 做 M5，进入图片和附件阶段设计。

## 风险点

- JavaFX Markdown 编辑体验会比 `HTMLEditor` 更朴素，需要用预览和快捷键弥补。
- HTML 到 Markdown 的转换不可能 100% 保留复杂样式，必须提供确认流程。
- 如果过早加入图片同步，会把编辑器迁移、数据迁移、文件同步三个风险叠加。
- 服务端需要兼容旧格式一段时间，否则已有客户端或已有数据会被破坏。

## 第一阶段完成定义

- 新建笔记默认保存为 Markdown。
- Markdown 内容本地保存、重启恢复、同步上传、远端拉取都保持原样。
- 旧 HTML 笔记不会丢失，并能通过明确入口转换。
- 客户端测试、服务端测试通过。
- `docs/api.md`、`docs/design.md`、`docs/tasklist.md` 已更新。
