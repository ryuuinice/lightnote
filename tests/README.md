# LightNote Tests

**Agent 5 (QA / Integration)** 负责此目录。

```text
tests/
├── smoke/            # 冒烟测试（核心链路快速验证）
├── vertical-slice/   # 端到端同步切片（真实 Server + 客户端核心）
├── perf/             # 性能基准（吞吐 / 增长 / FTS 探针）
└── hardening/        # 崩溃恢复 / 容错演练
```

## 运行

```bash
cd tests/smoke && cargo test
cd tests/vertical-slice && cargo test
```

perf 为 measure-only 基准（`cargo run --bin throughput` 等），结果记录见 `docs/CHANGELOG.md`。

## 规划中

以下目录尚未建立，属于后续扩展：

```text
tests/
├── integration/      # 双客户端 + 真实 Server
├── sync/             # 同步矩阵
├── crash/            # 崩溃恢复
├── blob/             # Blob 传输
└── e2e/              # 端到端
```
