package com.agentplatform.gateway.prompt;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.prompt.model.PromptStrategy;
import com.agentplatform.gateway.prompt.model.PromptTemplate;
import com.agentplatform.gateway.prompt.repository.PromptStrategyRepository;
import com.agentplatform.gateway.prompt.repository.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Prompt 策略服务
 * 支持按条件匹配策略、A/B 流量分配、灰度发布
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.prompt.enabled", havingValue = "true")
public class PromptStrategyService {

    private final PromptStrategyRepository strategyRepo;
    private final PromptTemplateRepository templateRepo;

    /**
     * 创建策略
     */
    public PromptStrategy createStrategy(CallerIdentity identity, String name, String description,
                                          Map<String, Object> matchConditions,
                                          List<PromptStrategy.TrafficRule> trafficRules,
                                          Integer priority) {
        PromptStrategy strategy = PromptStrategy.builder()
            .strategyId(UUID.randomUUID().toString())
            .tenantId(identity.getTenantId())
            .name(name)
            .description(description)
            .matchConditions(matchConditions)
            .trafficRules(trafficRules)
            .priority(priority != null ? priority : 0)
            .status("active")
            .build();

        return strategyRepo.save(strategy);
    }

    /**
     * 根据上下文选择 Prompt 模板
     * 
     * @param tenantId 租户 ID
     * @param context 上下文信息（task_type, model, tags 等）
     * @return 选中的模板，如果没有匹配的策略则返回 empty
     */
    public Optional<PromptTemplate> selectTemplate(String tenantId, Map<String, Object> context) {
        // 获取所有激活的策略，按优先级排序
        List<PromptStrategy> strategies = strategyRepo.findByTenantIdAndStatusOrderByPriorityDesc(
            tenantId, "active");

        for (PromptStrategy strategy : strategies) {
            if (matchesConditions(strategy.getMatchConditions(), context)) {
                // 根据流量规则选择模板
                PromptStrategy.TrafficRule selectedRule = selectByWeight(strategy.getTrafficRules());
                if (selectedRule != null) {
                    Optional<PromptTemplate> template = templateRepo.findByTenantIdAndNameAndVersion(
                        tenantId, selectedRule.getTemplateName(), selectedRule.getVersion());
                    
                    if (template.isPresent()) {
                        log.debug("Selected template '{}' v{} via strategy '{}' for context {}",
                            selectedRule.getTemplateName(), selectedRule.getVersion(),
                            strategy.getName(), context);
                        return template;
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 渲染模板（带策略选择）
     */
    public String renderWithStrategy(String tenantId, String defaultTemplateName,
                                      Map<String, Object> context,
                                      Map<String, Object> variables) {
        // 尝试通过策略选择模板
        Optional<PromptTemplate> strategyTemplate = selectTemplate(tenantId, context);
        
        if (strategyTemplate.isPresent()) {
            return renderTemplate(strategyTemplate.get().getTemplate(), variables);
        }

        // 回退到默认模板
        Optional<PromptTemplate> defaultTemplate = templateRepo
            .findFirstByTenantIdAndNameAndStatusOrderByVersionDesc(tenantId, defaultTemplateName, "active");

        if (defaultTemplate.isPresent()) {
            return renderTemplate(defaultTemplate.get().getTemplate(), variables);
        }

        throw new IllegalArgumentException("No template found for: " + defaultTemplateName);
    }

    /**
     * 列出租户的所有策略
     */
    public List<PromptStrategy> listStrategies(CallerIdentity identity) {
        return strategyRepo.findByTenantIdAndStatusOrderByPriorityDesc(
            identity.getTenantId(), "active");
    }

    /**
     * 更新策略的流量分配
     */
    public PromptStrategy updateTrafficRules(String strategyId, 
                                              List<PromptStrategy.TrafficRule> trafficRules) {
        PromptStrategy strategy = strategyRepo.findById(strategyId)
            .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + strategyId));

        // 验证权重总和为 100
        int totalWeight = trafficRules.stream()
            .mapToInt(PromptStrategy.TrafficRule::getWeight)
            .sum();
        if (totalWeight != 100) {
            throw new IllegalArgumentException("Traffic weights must sum to 100, got: " + totalWeight);
        }

        strategy.setTrafficRules(trafficRules);
        return strategyRepo.save(strategy);
    }

    /**
     * 禁用策略
     */
    public void disableStrategy(String strategyId) {
        PromptStrategy strategy = strategyRepo.findById(strategyId)
            .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + strategyId));
        strategy.setStatus("disabled");
        strategyRepo.save(strategy);
    }

    /**
     * 检查上下文是否匹配策略条件
     */
    private boolean matchesConditions(Map<String, Object> conditions, Map<String, Object> context) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, Object> entry : conditions.entrySet()) {
            String key = entry.getKey();
            Object expectedValue = entry.getValue();
            Object actualValue = context.get(key);

            if (actualValue == null) {
                return false;
            }

            // 支持列表匹配（任意一个匹配即可）
            if (expectedValue instanceof List<?> expectedList) {
                if (actualValue instanceof List<?> actualList) {
                    if (Collections.disjoint(expectedList, actualList)) {
                        return false;
                    }
                } else if (!expectedList.contains(actualValue)) {
                    return false;
                }
            } else if (!expectedValue.equals(actualValue)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 根据权重随机选择流量规则
     */
    private PromptStrategy.TrafficRule selectByWeight(List<PromptStrategy.TrafficRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        int totalWeight = rules.stream()
            .mapToInt(PromptStrategy.TrafficRule::getWeight)
            .sum();

        if (totalWeight <= 0) {
            return rules.get(0);
        }

        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;

        for (PromptStrategy.TrafficRule rule : rules) {
            cumulative += rule.getWeight();
            if (random < cumulative) {
                return rule;
            }
        }

        return rules.get(rules.size() - 1);
    }

    /**
     * 渲染模板
     */
    private String renderTemplate(String template, Map<String, Object> variables) {
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }
}
