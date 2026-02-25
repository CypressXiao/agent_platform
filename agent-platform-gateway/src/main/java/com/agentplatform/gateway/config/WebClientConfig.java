package com.agentplatform.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean("opaClient")
    public WebClient opaClient(WebClient.Builder builder,
                                @org.springframework.beans.factory.annotation.Value("${opa.url:http://localhost:8181}") String opaUrl) {
        return builder.baseUrl(opaUrl).build();
    }
}
