# 飞书文档同步与向量化方案设计

## 1. 背景与目标

- 当前 RAG 流程已支持按 `ChunkProfile`（SOP / Knowledge 等）进行分块、写入 Milvus。
- 业务文档主要沉淀在飞书（文档 + Wiki），需要统一接入，保证：
  1. **单次入库**：业务方提交飞书链接即可完成一次向量化。
  2. **增量更新**：文档变更后能够每日批量刷新，并支持手动触发。
  3. **目录 / Wiki 批量**：指定空间或目录可自动发现新增文档并向量化。
  4. **统一治理**：权限、失败重试、审计、配置、profile 与 collection 绑定可控。

## 2. 使用场景

| 场景 | 描述 | 触发方式 | 说明 |
| ---- | ---- | ---- | ---- |
| 单文档按需 | 外部服务提供飞书链接，立即向量化 | API / CLI | 向量化后记录 `doc_token`，后续增量扫描 |
| 自有服务预加载 | 我方先初始化一批 SOP / 知识文档供下游使用 | 运维预设 | 统一配置 profile、collection，扩展时只需追加链接 |
| Wiki / 目录自动纳管 | 维护空间/目录白名单，遍历其中所有文档 | 定时任务 | 发现新增节点自动入库，已有节点按 revision 增量 |

## 3. 整体架构

```text
+---------------------------+      +--------------------------+
|  FeishuSyncScheduler      | ---> |  Sync Task Queue         |
|  (定时 / 手动 / Webhook) |      |  (pending/running/done)  |
+---------------------------+      +--------------------------+
            |                                   |
            v                                   v
   +-----------------+                 +-----------------------+
   | FeishuFetcher   | --Markdown-->   | ChunkingPipeline      |
   | (token, diff)   |                 | (Profile + Milvus)    |
   +-----------------+                 +-----------------------+
            |                                   |
            +----> Registry & Audit <-----------+
```
- **FeishuSyncScheduler**：
  - 定时扫描 registry，查 revision 是否更新。
  - 手动触发（运营后台 / CLI）直接写入任务队列。
  - 可选监听 Webhook（文档更新 / 删除事件），作为“任务入队”的附加来源。
- **FeishuFetcher**：
  - 负责换取 token、遍历目录 / Wiki、下载文档内容。
  - 将飞书原始内容转换为符合 `docs/templates/document-format-guide.md` 的 Markdown（补全元信息块）。
- **ChunkingPipeline**：复用 `ChunkProfileRegistry + DocumentChunker + ChunkingController`，根据 profile 生成 chunk，写 Milvus。
- **Registry & Audit**：落库存储文档注册信息、最后 revision、collection、状态以及审计日志。

## 4. 权限策略

1. **企业自建应用 + 机器人账号**：在飞书后台授予知识库 / 云文档读取权限，并把目标空间 / 目录授权给机器人账号。
2. **统一 token 管理**：在服务端获取 `tenant_access_token`（或必要时的 `user_access_token`），缓存并自动刷新。
3. **权限失效处理**：若调用返回 403（`insufficient_permission`），记录状态 `PERMISSION_DENIED` 并告警；若返回 404，视为文档被删除，走下线流程。

## 5. Registry 设计（示例）

| 字段 | 说明 |
| ---- | ---- |
| `id` | 主键 |
| `doc_token` | 飞书文档唯一 ID；目录场景可存 `folder_token`（另建子表） |
| `source_type` | `SINGLE` / `WIKI` / `FOLDER` / `WEBHOOK` |
| `profile` | `sop` / `knowledge` / ...，决定 chunk profile |
| `collection` | 映射 Milvus collection 名称 / pattern |
| `tenant` / `scene` | 用于 collection 细分和权限隔离 |
| `last_revision` | 最近一次成功同步的 revision ID |
| `last_hash` | 可选，记录内容 hash，便于 diff |
| `status` | `ACTIVE` / `DELETED` / `PERMISSION_DENIED` 等 |
| `priority` | 任务优先级（关键 SOP 可置高） |
| `metadata` | JSON，额外标签、目录路径、提交人等 |

任务表：`task_id / doc_token / revision / trigger_type / status / retry_count / error_message / created_at / updated_at`。

## 6. 数据流 & 处理步骤

1. **注册**：
   - 单文档场景：首次向量化时即写入 registry，记录 doc_token、profile、collection / tenant / scene 信息。
   - Wiki / 目录：初始化脚本遍历树，所有节点写入 registry（若已存在则更新 metadata）。
2. **定时扫描**：
   - 根据 registry 分片拉取最新 revision；可按 tenant / scene 分片。
   - 发现 `revision > last_revision` 则写入任务表。
3. **任务执行**：
   - Worker 串行处理同一个 doc_token，确保幂等。
   - 调飞书内容 API → Markdown 转换器 → ChunkingController.chunkAndStore（携带 profile / collection）。
   - 成功后更新 registry 的 `last_revision`、`last_sync_at`。
4. **手动 / 实时触发**：
   - 后台 / CLI / 机器人把 doc_token 列表写入任务表（`trigger_type=MANUAL`），Worker 复用同一逻辑。
   - Webhook 事件写入任务表，便于和定时任务共用重试机制。

## 7. 异常、删除与幂等

- **Failed Retries**：任务执行失败写入 error_message，按指数退避重试；超过阈值进入死信并告警。
- **幂等键**：`doc_token + revision`，重复任务直接判定已处理。
- **删除检测**：
  1. Webhook 订阅“文档删除”事件，实时下线。
  2. 定时扫描若读取文档返回 404，同样标记 `DELETED` 并触发向量库删除（软删除或 Milvus delete）。
- **权限失效**：捕获 403 后标记 `PERMISSION_DENIED`，写审计日志，并通过飞书 Bot / 告警系统通知管理员；权限恢复后可手动重新入队。

## 8. Markdown 转换与媒体处理

- 统一实现 `FeishuDocumentConverter`：解析飞书富文本 JSON，生成符合模板的 Markdown（含“文档元信息块”字段），保持标题层级，便于 profile 判断 chunk 类型。
- **图片 / 附件策略**：
  1. 保留 `![image](url)` 占位，并在 metadata 中写 `has_image=true`；
  2. 需要更好语义时调用 OCR / 描述服务生成文本（可配置开关）；
  3. 对不重要图片标注“[图片省略]”。
- **表格处理**：转换成 Markdown table；如超大表格，可落地为列表或附带 CSV 链接。
- **格式兼容说明**：
  - ✅ 文本、标题、列表、引用、代码块、TODO、分割线等均可直接映射为 Markdown。
  - ⚠️ 图片仅能生成 `feishu://image/{token}` 占位，需要渲染端二次拉取；Callout/Grid 只会输出其中的文本内容，背景色与图标会丢失。
  - ⚠️ 表格会转成 Markdown 表格，无法保留列宽/合并单元格等高级样式。
  - ⚠️ 内嵌第三方控件、复杂小组件（Kanban、流程图等）会退化成占位文本或简单引用，请在原文中提供文字说明以便检索。
  - ⚠️ 未识别的 `block_type` 将递归提取其子节点文本，若完全无法解析会被忽略，因此请尽量使用模板中推荐的基础块类型。

## 9. 配置、审计与监控

- **配置管理**：Apollo 仅承担少量全局配置（飞书 App 凭证、空间白名单、扫描频率、QPS 等）。大规模“文档 / collection 映射”统一由数据库 registry 维护，避免在配置中心堆积长列表。
- **权限校验**：复用现有 `CallerIdentity` / 网关鉴权，对外部触发接口做鉴权与限流。
- **审计**：同步操作写入现有审计体系，字段包括触发人 / 方式、doc_token、revision、collection、耗时、结果。
- **监控告警**：
  - 任务队列积压、连续失败、403 / 404 数量、飞书 API QPS、Milvus 写入错误、权限失效告警。

## 10. 开发优先级建议

1. 搭建 `FeishuDocumentConverter`（含 Markdown 模板对齐）与 registry / 任务表。
2. 实现定时扫描 + Worker 执行链路，先支持单文档 / 人工注册。
3. 扩展 Wiki / 目录遍历能力，纳管更多文档。
4. 补齐删除 / 权限失效处理、图片策略、审计与告警。
5. 评估 Webhook 事件，作为增量触发与删除检测的优化手段。

## 11. 风险与待定问题

- **权限碎片化**：若业务把文档放在不同空间，需要额外流程确保机器人有读取权。
- **飞书 API 限流**：大批量同步需做好分页、缓存、退避策略；必要时多个租户并行。
- **Markdown 转换准确性**：复杂排版（多栏、复杂表格、嵌套控件）需要逐步调优。
- **多 profile 扩展**：后续新增 FAQ / Product Doc profile 时，registry 与转换器要支持映射。

## 12. Collection 命名与 Profile 校验

1. **Profile 校验**：入口仅允许 `ChunkProfileRegistry` 中注册的名称（如 `sop`、`knowledge`）；请求校验失败直接拒绝，并在对接文档中说明各 profile 的适用场景、chunk 规则与默认 collection pattern。
2. **命名策略**：
   - 推荐 `{tenant}_{scene}_{profile}_{model}`，例如 `tenantA_onboarding_sop_qwen3_vl_embedding`，保证租户 / 场景隔离。
   - 若调用方未提供 tenant / scene，则退回 profile 默认 pattern（如 `sop_{model}`），但强烈建议传入，以免数据混杂。
3. **自定义 collection**：
   - 当调用方指定自定义 collection 时，系统只负责校验其存在性；若不存在，直接报错提示先创建（避免我们误判其 schema）。
   - 使用内置 profile 时，可自动按 pattern 生成 collection 名称；仍会在 registry 中记录真实 collection，供后续增量同步使用。

## 13. 触发与通知策略

- **热加载**：registry 存储所有“文档-collection”映射；通过内部 API / 运维后台增删记录即可生效，Scheduler 每次都从 DB 读取，无需重启。Apollo 仅用于广播“扫描开关、默认空间”等全局配置。
- **删除 / 权限通知**：
  - 删除：Webhook + 定时扫描双保险，触发后向量库下线并在审计日志中记录来源。
  - 权限失效：捕获 403 后，除状态标记外，还可通过飞书机器人或告警平台推送消息给管理员，内容包括 doc_token、空间、最近一次成功同步时间。

> 如需进一步细化（表结构 DDL、任务状态机、转换器示例等），可在本设计基础上拆分任务。
