package com.agentplatform.gateway.prompt;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.prompt.model.PromptTemplate;
import com.agentplatform.gateway.prompt.repository.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 管理服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.prompt.enabled", havingValue = "true")
public class PromptService {

    private final PromptTemplateRepository templateRepo;

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    /**
     * 创建 Prompt 模板
     */
    public PromptTemplate create(CallerIdentity identity, String name, String description,
                                  String template, List<String> variables) {
        String tenantId = identity.getTenantId();

        List<String> extractedVars = variables != null ? variables : extractVariables(template);

        PromptTemplate promptTemplate = PromptTemplate.builder()
            .templateId(UUID.randomUUID().toString())
            .tenantId(tenantId)
            .name(name)
            .description(description)
            .template(template)
            .variables(extractedVars)
            .version(1)
            .status("active")
            .build();

        return templateRepo.save(promptTemplate);
    }

    /**
     * 获取最新版本的模板
     */
    @Cacheable(value = "promptTemplates", key = "#tenantId + ':' + #name")
    public Optional<PromptTemplate> getLatest(String tenantId, String name) {
        return templateRepo.findFirstByTenantIdAndNameAndStatusOrderByVersionDesc(
            tenantId, name, "active");
    }

    /**
     * 获取指定版本的模板
     */
    public Optional<PromptTemplate> getByVersion(String tenantId, String name, Integer version) {
        return templateRepo.findByTenantIdAndNameAndVersion(tenantId, name, version);
    }

    /**
     * 列出租户的所有模板
     */
    public List<PromptTemplate> list(CallerIdentity identity) {
        return templateRepo.findByTenantIdAndStatus(identity.getTenantId(), "active");
    }

    /**
     * 渲染模板
     */
    public String render(String tenantId, String templateName, Map<String, Object> variables) {
        PromptTemplate template = getLatest(tenantId, templateName)
            .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateName));

        return renderTemplate(template.getTemplate(), variables);
    }

    /**
     * 渲染模板（指定版本）
     */
    public String render(String tenantId, String templateName, Integer version, 
                         Map<String, Object> variables) {
        PromptTemplate template = getByVersion(tenantId, templateName, version)
            .orElseThrow(() -> new IllegalArgumentException(
                "Template not found: " + templateName + " v" + version));

        return renderTemplate(template.getTemplate(), variables);
    }

    /**
     * 创建新版本
     */
    public PromptTemplate createNewVersion(CallerIdentity identity, String name, 
                                            String template, String description) {
        String tenantId = identity.getTenantId();

        Integer maxVersion = templateRepo.findByTenantIdAndName(tenantId, name).stream()
            .map(PromptTemplate::getVersion)
            .max(Integer::compareTo)
            .orElse(0);

        PromptTemplate newVersion = PromptTemplate.builder()
            .templateId(UUID.randomUUID().toString())
            .tenantId(tenantId)
            .name(name)
            .description(description)
            .template(template)
            .variables(extractVariables(template))
            .version(maxVersion + 1)
            .status("active")
            .build();

        return templateRepo.save(newVersion);
    }

    /**
     * 使用 Mustache 风格渲染模板
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

    /**
     * 从模板中提取变量名
     */
    private List<String> extractVariables(String template) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        return matcher.results()
            .map(m -> m.group(1))
            .distinct()
            .toList();
    }
}
