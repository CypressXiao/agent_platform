# SOP 文档分块指南

本指南说明如何将 SOP 文档正确分块并存入向量库，以获得最佳的检索效果。

---

## 分块原则

| 原则 | 说明 |
|------|------|
| **一个问题一个答案** | 每个 chunk 应该能独立回答一个问题 |
| **自包含** | 每个 chunk 独立可理解，不依赖上下文 |
| **适当粒度** | 不要太大（检索不精确）也不要太小（信息不完整） |
| **保留结构** | 保留标题、步骤号等结构信息 |

---

## 分块策略

### 1. 概述 (Overview)

将 SOP 的概述部分作为单独的 chunk。

**示例**:
```
【员工入职流程 - 概述】
本流程规范了员工入职的标准操作步骤，确保新员工能够顺利完成入职手续、获得必要的系统权限、并快速融入团队。
```

**Metadata**:
```json
{
  "sop_name": "员工入职流程",
  "sop_id": "sop-onboarding-001",
  "version": "v2.0",
  "chunk_type": "overview",
  "keywords": ["入职", "新员工", "报到"]
}
```

---

### 2. 步骤 (Step)

每个步骤作为单独的 chunk，包含完整的操作说明。

**示例**:
```
【员工入职流程 - 步骤1: 资料准备】

目的: 收集新员工入职所需的全部材料

操作说明:
1. 准备身份证原件及复印件（正反面）
2. 准备学历证书原件及复印件
3. 准备离职证明（如有上一份工作）
4. 准备1寸白底证件照2张
5. 准备银行卡信息（用于工资发放）

输出物: 完整的入职材料包

注意事项:
- 所有复印件需清晰可辨认
- 学历证书需提供最高学历
- 银行卡需为本人名下的储蓄卡
```

**Metadata**:
```json
{
  "sop_name": "员工入职流程",
  "sop_id": "sop-onboarding-001",
  "version": "v2.0",
  "chunk_type": "step",
  "step_number": 1,
  "step_title": "资料准备",
  "keywords": ["入职材料", "身份证", "学历证书", "离职证明"]
}
```

---

### 3. FAQ

每个 Q&A 作为单独的 chunk。

**示例**:
```
【员工入职流程 - FAQ】

Q: 入职当天需要带哪些材料?

A: 入职当天需要携带以下材料：身份证原件及复印件、最高学历证书原件及复印件、离职证明（如有）、1寸白底证件照2张、银行卡信息。建议提前准备好，避免遗漏。
```

**Metadata**:
```json
{
  "sop_name": "员工入职流程",
  "sop_id": "sop-onboarding-001",
  "version": "v2.0",
  "chunk_type": "faq",
  "question": "入职当天需要带哪些材料",
  "keywords": ["入职材料", "入职当天"]
}
```

---

## Metadata 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sop_name` | string | ✅ | SOP 名称，用于过滤和上下文补全 |
| `sop_id` | string | ✅ | SOP 唯一标识 |
| `version` | string | ✅ | 版本号，用于过滤最新版本 |
| `chunk_type` | enum | ✅ | 类型：overview / step / faq / appendix |
| `step_number` | int | 步骤必填 | 步骤编号，用于排序和上下文补全 |
| `step_title` | string | 步骤必填 | 步骤标题 |
| `question` | string | FAQ必填 | 问题内容 |
| `keywords` | array | 推荐 | 关键词，增强 BM25 检索 |
| `department` | string | 可选 | 适用部门，用于权限过滤 |
| `effective_date` | date | 可选 | 生效日期 |
| `entities` | array | 可选 | 实体列表，用于 GraphRAG |

---

## 分块大小建议

| 类型 | 建议大小 | 说明 |
|------|----------|------|
| 概述 | 100-300 字 | 简洁明了 |
| 步骤 | 200-500 字 | 包含完整操作说明 |
| FAQ | 100-300 字 | 问题 + 完整答案 |

**注意**: 
- 如果步骤过长（>500字），考虑拆分为子步骤
- 如果步骤过短（<100字），考虑合并相关内容

---

## 存储 API 调用示例

```bash
curl -X POST http://localhost:8080/api/v1/vectors/store \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "collection": "sop_knowledge_base",
    "documents": [
      {
        "id": "sop-onboarding-001-step-1",
        "content": "【员工入职流程 - 步骤1: 资料准备】\n\n目的: 收集新员工入职所需的全部材料\n\n操作说明:\n1. 准备身份证原件及复印件（正反面）\n2. 准备学历证书原件及复印件\n3. 准备离职证明（如有上一份工作）\n4. 准备1寸白底证件照2张\n5. 准备银行卡信息（用于工资发放）\n\n输出物: 完整的入职材料包\n\n注意事项:\n- 所有复印件需清晰可辨认\n- 学历证书需提供最高学历\n- 银行卡需为本人名下的储蓄卡",
        "metadata": {
          "sop_name": "员工入职流程",
          "sop_id": "sop-onboarding-001",
          "version": "v2.0",
          "chunk_type": "step",
          "step_number": 1,
          "step_title": "资料准备",
          "keywords": ["入职材料", "身份证", "学历证书", "离职证明"]
        }
      }
    ]
  }'
```

---

## RAG 查询示例

```bash
# 使用 Advanced RAG 模式查询
curl -X POST http://localhost:8080/api/v1/rag/query \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "collection": "sop_knowledge_base",
    "query": "入职需要准备什么材料",
    "mode": "ADVANCED",
    "enableQueryRewrite": true,
    "enableRerank": true,
    "enableContextCompletion": true
  }'
```

---

## 常见问题

### Q: 如何处理条件分支?

A: 将条件分支作为步骤的一部分，在 content 中明确列出：

```
条件分支:
- 如果是技术岗位：额外开通代码仓库、服务器访问权限
- 如果是销售岗位：额外开通CRM系统权限
```

### Q: 如何处理跨 SOP 的引用?

A: 在 metadata 中添加 `related_sops` 字段：

```json
{
  "related_sops": ["sop-leave-001", "sop-expense-001"]
}
```

### Q: 如何处理版本更新?

A: 
1. 新版本使用新的 version 号
2. 旧版本可以保留或删除
3. 查询时可以通过 metadata 过滤指定版本
