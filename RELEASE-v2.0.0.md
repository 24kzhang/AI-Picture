# V2.0.0 发布说明：修图 Agent 与 AI 协同云图库融合

> 本文件随代码提交，用于部署与回滚说明。

## 1. 版本内容

- 云图库（Spring Boot + Vue）融合修图 Agent（FastAPI）作为内部微服务。
- 新增能力：Agent 智能精修（单图）、批量精修、营销图、投放尺寸、ZIP 导出、图片版本历史与恢复。
- Spring Boot 为唯一对外入口；浏览器不直接访问 Agent，也不持有 Agent 令牌。
- 图片替换采用「显式确认 + 乐观锁 + 版本快照」；未确认的 Agent 结果只作为草稿。
- 快捷编辑与 Agent 精修共用统一编辑租约（Redis 锁），互斥编辑同一张图片。

## 2. 部署顺序

1. 执行 MySQL 增量迁移：`backend/sql/migrations/20260916_v2_0_0_agent_integration.sql`
2. 执行 Agent PostgreSQL 迁移：`services/retouch-agent/backend` 下 `python migrate.py`
3. 部署 Agent API 与 Worker（保持入口关闭，仅内网可达）
4. 部署 Spring Boot 与 Vue
5. 健康检查：`GET /api/agent-internal/health`（Spring）、`GET /api/health`（Agent）
6. 管理员在系统设置中开启 Agent 能力开关，完成冒烟后对普通用户开放

## 3. 关键配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `AGENT_EDIT_ENABLED` | true | Agent 智能精修总开关；关闭后图库主体与快捷编辑不受影响 |
| `AGENT_SERVICE_URL` | http://127.0.0.1:7302 | Agent 内部地址 |
| `AGENT_SERVICE_SECRET` | 空 | 与 Agent 共享的 HMAC 密钥（≥32 字节），集成模式必填 |
| `SERVER_PORT` | 8080 | 后端端口（可覆盖） |
| `GALLERY_PUBLIC_URL` | http://localhost:8080 | 浏览器可达地址（导出下载链接使用） |
| `INTEGRATION_MODE`（Agent） | false | true 时关闭 Agent 独立注册登录，仅接受服务令牌 |
| `STORAGE_BACKEND`（Agent） | s3 | 生产使用 `bridge`，经云图库签名 URL 访问腾讯 COS |
| `GALLERY_BRIDGE_URL`（Agent） | 空 | 云图库桥接基地址，如 `http://<gallery>/api/agent-internal` |
| `AGENT_TEMP_RETENTION_DAYS` | 7 | Agent 临时对象（agent-temp/）保留天数 |

## 4. 发布与回滚

- 发布：`release/v2.0.0` 验收通过后非快进合并到 `main`，在合并提交打 `v2.0.0` 标签，并将修复回合到 `develop`。
- 回滚：
  - 优先关闭 `AGENT_EDIT_ENABLED`（图库主体与快捷编辑继续可用）。
  - 保留新增表与版本记录，不执行破坏性回滚（如需可执行 `..._rollback.sql`，仅限开发/演练环境）。
  - Spring/Vue 可回滚到 V1 镜像；Agent 停止不影响现有图库。

## 5. 已知限制（本机环境相关，非代码缺陷）

- 向量重建依赖 DashScope 多模态嵌入；密钥失效时 Outbox 会持续退避重试，图片提交本身不受影响（最终一致）。
- 自然语言规划需要有效的 DashScope Key（`DASHSCOPE_API_KEY`）；缺失时对话指令返回明确错误，工具按钮与批量流程不受影响。
- 批量页面单次列出空间前 20 张（与批量上限一致）。
