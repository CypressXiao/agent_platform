package com.agentplatform.gateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 内容安全服务
 * 提供 PII 检测、DLP、越狱检测、有害内容过滤
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentSecurityService {

    // PII 检测正则
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "1[3-9]\\d{9}|\\+86\\s*1[3-9]\\d{9}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
        "\\d{16,19}");
    private static final Pattern IP_PATTERN = Pattern.compile(
        "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    // 越狱检测关键词
    private static final List<String> JAILBREAK_KEYWORDS = List.of(
        "ignore previous instructions",
        "忽略之前的指令",
        "disregard all prior",
        "pretend you are",
        "假装你是",
        "act as if you have no restrictions",
        "DAN mode",
        "developer mode",
        "bypass safety"
    );

    // 有害内容关键词（简化版）
    private static final List<String> HARMFUL_KEYWORDS = List.of(
        "如何制造炸弹",
        "how to make a bomb",
        "如何自杀",
        "how to kill"
    );

    /**
     * 检测并过滤输入内容
     */
    public ContentFilter filterInput(String content) {
        if (content == null || content.isEmpty()) {
            return ContentFilter.passed();
        }

        List<String> issues = new ArrayList<>();

        // 1. 越狱检测
        if (detectJailbreak(content)) {
            issues.add("JAILBREAK_ATTEMPT");
            return ContentFilter.builder()
                .passed(false)
                .reason("Detected potential jailbreak attempt")
                .riskLevel(ContentFilter.RiskLevel.CRITICAL)
                .detectedIssues(issues)
                .build();
        }

        // 2. 有害内容检测
        if (detectHarmfulContent(content)) {
            issues.add("HARMFUL_CONTENT");
            return ContentFilter.builder()
                .passed(false)
                .reason("Detected potentially harmful content")
                .riskLevel(ContentFilter.RiskLevel.HIGH)
                .detectedIssues(issues)
                .build();
        }

        // 3. PII 检测
        List<String> piiTypes = detectPII(content);
        if (!piiTypes.isEmpty()) {
            issues.add("PII_DETECTED");
            String sanitized = sanitizePII(content);
            return ContentFilter.builder()
                .passed(true) // PII 不阻止，但需要脱敏
                .riskLevel(ContentFilter.RiskLevel.MEDIUM)
                .detectedIssues(issues)
                .piiTypes(piiTypes)
                .sanitizedContent(sanitized)
                .build();
        }

        return ContentFilter.passed();
    }

    /**
     * 检测并过滤输出内容
     */
    public ContentFilter filterOutput(String content) {
        if (content == null || content.isEmpty()) {
            return ContentFilter.passed();
        }

        List<String> issues = new ArrayList<>();

        // 1. PII 泄露检测
        List<String> piiTypes = detectPII(content);
        if (!piiTypes.isEmpty()) {
            issues.add("PII_LEAKAGE");
            String sanitized = sanitizePII(content);
            return ContentFilter.builder()
                .passed(true)
                .riskLevel(ContentFilter.RiskLevel.MEDIUM)
                .detectedIssues(issues)
                .piiTypes(piiTypes)
                .sanitizedContent(sanitized)
                .build();
        }

        // 2. 有害输出检测
        if (detectHarmfulContent(content)) {
            issues.add("HARMFUL_OUTPUT");
            return ContentFilter.builder()
                .passed(false)
                .reason("Output contains potentially harmful content")
                .riskLevel(ContentFilter.RiskLevel.HIGH)
                .detectedIssues(issues)
                .build();
        }

        return ContentFilter.passed();
    }

    /**
     * 检测 PII
     */
    public List<String> detectPII(String content) {
        List<String> piiTypes = new ArrayList<>();

        if (PHONE_PATTERN.matcher(content).find()) {
            piiTypes.add("PHONE");
        }
        if (EMAIL_PATTERN.matcher(content).find()) {
            piiTypes.add("EMAIL");
        }
        if (ID_CARD_PATTERN.matcher(content).find()) {
            piiTypes.add("ID_CARD");
        }
        if (BANK_CARD_PATTERN.matcher(content).find()) {
            piiTypes.add("BANK_CARD");
        }
        if (IP_PATTERN.matcher(content).find()) {
            piiTypes.add("IP_ADDRESS");
        }

        return piiTypes;
    }

    /**
     * 脱敏 PII
     */
    public String sanitizePII(String content) {
        String result = content;

        // 手机号脱敏
        result = PHONE_PATTERN.matcher(result).replaceAll(m -> {
            String phone = m.group();
            if (phone.length() >= 11) {
                return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
            }
            return "***PHONE***";
        });

        // 邮箱脱敏
        result = EMAIL_PATTERN.matcher(result).replaceAll(m -> {
            String email = m.group();
            int atIndex = email.indexOf('@');
            if (atIndex > 2) {
                return email.substring(0, 2) + "***" + email.substring(atIndex);
            }
            return "***@***.***";
        });

        // 身份证脱敏
        result = ID_CARD_PATTERN.matcher(result).replaceAll(m -> {
            String id = m.group();
            return id.substring(0, 6) + "********" + id.substring(id.length() - 4);
        });

        // 银行卡脱敏
        result = BANK_CARD_PATTERN.matcher(result).replaceAll(m -> {
            String card = m.group();
            if (card.length() >= 8) {
                return card.substring(0, 4) + " **** **** " + card.substring(card.length() - 4);
            }
            return "****";
        });

        // IP 脱敏
        result = IP_PATTERN.matcher(result).replaceAll("***.***.***.***");

        return result;
    }

    /**
     * 检测越狱尝试
     */
    private boolean detectJailbreak(String content) {
        String lowerContent = content.toLowerCase();
        for (String keyword : JAILBREAK_KEYWORDS) {
            if (lowerContent.contains(keyword.toLowerCase())) {
                log.warn("Jailbreak keyword detected: {}", keyword);
                return true;
            }
        }
        return false;
    }

    /**
     * 检测有害内容
     */
    private boolean detectHarmfulContent(String content) {
        String lowerContent = content.toLowerCase();
        for (String keyword : HARMFUL_KEYWORDS) {
            if (lowerContent.contains(keyword.toLowerCase())) {
                log.warn("Harmful content keyword detected: {}", keyword);
                return true;
            }
        }
        return false;
    }

    /**
     * 批量检测
     */
    public Map<String, ContentFilter> batchFilter(List<String> contents) {
        Map<String, ContentFilter> results = new HashMap<>();
        for (int i = 0; i < contents.size(); i++) {
            results.put("content_" + i, filterInput(contents.get(i)));
        }
        return results;
    }
}
