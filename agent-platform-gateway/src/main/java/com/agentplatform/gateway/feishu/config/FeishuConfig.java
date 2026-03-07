package com.agentplatform.gateway.feishu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "feishu")
public class FeishuConfig {

    private String appId;
    private String appSecret;
    private String baseUrl = "https://open.feishu.cn/open-apis";

    private Token token = new Token();
    private Sync sync = new Sync();
    private Retry retry = new Retry();

    @Data
    public static class Token {
        private int refreshBeforeExpireSeconds = 300;
        private int cacheSeconds = 7200;
    }

    @Data
    public static class Sync {
        private boolean enabled = true;
        private String cronExpression = "0 0 2 * * ?";
        private int batchSize = 10;
        private int maxConcurrent = 5;
        private int scanIntervalHours = 24;
    }

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private long initialIntervalMs = 1000;
        private double multiplier = 2.0;
        private long maxIntervalMs = 60000;
    }
}
