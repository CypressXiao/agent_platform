# Prompt 管理模块

## 概述
Prompt 模板管理服务，为 Agent 服务提供 Prompt 模板存储、版本管理和渲染能力。

## 核心能力
1. **模板存储** - 存储 Prompt 模板，支持多租户隔离
2. **版本管理** - 支持模板版本控制
3. **模板渲染** - 使用 Mustache 模板引擎渲染变量

## 技术栈
- **模板引擎**: Mustache (via Spring AI PromptTemplate)
- **存储**: MySQL
- **缓存**: Caffeine

## 内置工具
| 工具名 | 功能 |
|--------|------|
| `prompt_render` | 渲染 Prompt 模板 |

## 数据模型
```sql
CREATE TABLE prompt_template (
    template_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    template TEXT NOT NULL,
    variables JSON,
    version INT DEFAULT 1,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_name_version (tenant_id, name, version)
);
```

## 配置
```yaml
agent-platform:
  prompt:
    enabled: true
```

## 使用示例
```json
// prompt_render
{
  "template_name": "customer_support",
  "variables": {
    "customer_name": "张三",
    "issue": "订单查询"
  }
}
```
