# Agent Platform Roadmap v2

> **Document purpose**: capture post-Phase3 enhancements for the Agent Platform after completing the Phase1–3 scope defined in `gap-analysis.md` (v1.0, 2026-02-28). This v2 roadmap focuses on capabilities needed when exposing the platform as an internal Agent PaaS for other services.

## 1. Baseline recap (from v1 gap-analysis)

- Phase 1–3 items (Chunking, MCP versioning, REST 审计、JSON Schema 校验、事件模型、Prompt 策略、重试/超时、评测、Webhooks、数据保留、内容安全) have been delivered and verified by `mvn clean install -DskipTests` on 2026-03-04.
- Current platform already provides governance, auditing, strategy management, evaluation, and observability features required for first-party agent workloads.

## 2. New requirements overview

| Priority | Theme | Goal |
| --- | --- | --- |
| **P00** | Async Tool Orchestration & Long-Running Jobs | 支持工具声明同步/异步模式，提供 Callback/Queue 交付、统一 Job 协议与 Workflow 等待聚合能力，解决长耗时工具阻塞问题。 |
| **P0** | Workflow/Graph 编排、策略工作台 | 让其他服务能够可视化编排 Agent 流程、自助配置 Prompt/重试/内容安全策略，并基于事件模型进行回放调试。 |
| **P1** | 生态与自动化扩展 | 形成标准化工具市场、自动评测/A-B 工作流、丰富事件订阅通道，降低跨团队集成门槛。 |
| **P2** | 企业级能力 | 构建多租户配额/计费、合规治理与 SDK/CLI 工具包，使平台具备 PaaS 级运营与合规能力。 |

## 3. Priority details

### P00: Async Tool Orchestration & Long-Running Jobs

- **需求**：工具可声明同步/异步模式，异步工具支持 Callback 或 Queue 两种结果交付方式，平台提供统一 Job 协议与 Workflow 等待聚合能力。
- **要点**：
  1. **工具注册扩展**：`Tool` 实体新增字段（execution_mode, result_delivery, callback_url, callback_auth, queue_topic, timeout_ms），通过注册 API 管理；同步工具只需 execution_mode=SYNC（默认），无需额外字段。
  2. **统一 Job 协议**：异步工具标准返回 `{ "status": "PENDING", "job_id": "...", "estimated_wait_seconds": ... }`；JobService 维护状态机（PENDING/RUNNING/SUCCEEDED/FAILED/TIMEOUT），暴露 `/api/v1/jobs/{jobId}`、`POST /api/v1/jobs/{jobId}/callback`、Webhook/SSE 事件。
  3. **执行路径**：ToolDispatcher 识别 ASYNC → AsyncAdapter。Callback：平台按配置回调；Queue：平台写入队列，订阅方（平台 Worker 或第三方）消费并回写 JobService。Queue 覆盖“派活+第三方执行”场景，暂不实现 Poll。
  4. **Workflow/Agent 范式**：Workflow 节点记录等待的 jobIds，提供 `waitFor(jobIds...)` 聚合；全部完成后再继续。会话若已切换，则把 job 结果作为通知事件，不自动写入记忆。平台文档说明推荐处理方式（等待提示、结果回灌等）。
  5. **治理与观测**：指标（等待/运行时长、成功率、超时率）；Resilience（异步任务重试、限流、熔断）；Retention（job 数据归档/清理）；Evaluation 纳入质量分析。回调认证由平台统一封装（API Key、HMAC、OAuth2 等），工具通过配置声明即可。

### P0: Developer & Governance Experience (立即启动)

1. **Workflow/Graph Orchestrator**
   - 可视化节点：Prompt、Tool、LLM、Branch、Loop、Webhook
   - 运行时与事件模型打通：提供 runId/stepId 级别的追踪与断点回放
   - 对接策略引擎：节点可引用 Prompt 策略、重试策略、内容安全策略
2. **在线调试 / 回放工作台**
   - 使用已有 `ToolCallEvent` + `EventStorageService` API
   - 支持变量/上下文查看、即时修改 Prompt、重跑单步
3. **Guardrail & Strategy Console**
   - 可视化配置各种策略（Prompt、重试/超时、内容安全门槛、Webhook）
   - 支持版本化、回滚与审批流程

### P1: Ecosystem & Automation (中期)

1. **Tool/Plugin Registry**
   - 标准工具注册流程（schema 校验、健康检查、验签）
   - 工具评分、状态监控、版本发布
2. **自动评测 & A/B Framework**
   - 基于 `EvaluationService` 补充基准数据集、实验管理（实验组/对照组）、统计报表
3. **事件总线扩展**
   - 在 Webhook 基础上增加 Kafka/MQ 推送、事件重放 API、订阅管理

### P2: Enterprise-grade Capabilities (长期)

1. **Multi-tenant quota & billing**
   - Token/调用量、向量存储容量、LLM 费用核算
   - 可插拔 KMS、租户级密钥隔离
2. **Compliance & Audit Plus**
   - 审计报表导出、敏感操作双人审批
   - 数据驻留策略、GDPR/国密要求
3. **Developer Toolkit**
   - SDK/CLI、模板化脚手架、Terraform Provider 或 Helm Chart

## 4. Versioning & next steps

- `gap-analysis.md` 保持为 v1.0（Phase1–3 交付状态）。
- 本文 `agent-platform-roadmap-v2.md` 作为 v2 需求基线。
- 建议：
  1. 在团队周会确认 P0 项目的负责人与时间表。
  2. 将 Workflow/Graph 与 Strategy Console 纳入下一轮迭代 backlog。
  3. 在 Workbench 中建立对应的 Epic/Story，引用本文件作为需求说明。
