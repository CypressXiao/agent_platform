package com.agentplatform.gateway.rag.component;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 宽进严出策略配置
 * Retrieval 阶段多检索，Rerank 阶段严格筛选
 */
@Component
@Data
@ConfigurationProperties(prefix = "agent-platform.rag.wide-to-tight")
public class WideToTightStrategy {

    /**
     * 默认策略
     */
    private DefaultStrategy defaultStrategy = new DefaultStrategy();

    /**
     * 按场景的策略
     */
    private Map<String, ScenarioStrategy> scenarios = new HashMap<>();

    /**
     * 按文档类型的策略
     */
    private Map<String, DocumentTypeStrategy> documentTypes = new HashMap<>();

    @Data
    public static class DefaultStrategy {
        /**
         * Retrieval 阶段的检索倍数（宽进）
         */
        private double retrieveMultiplier = 3.0;

        /**
         * Retrieval 阶段的阈值降低比例（宽进）
         */
        private double thresholdReduction = 0.2;

        /**
         * Rerank 阶段的最终输出比例（严出）
         */
        private double rerankOutputRatio = 1.0; // 1.0 表示完全按用户 topK

        /**
         * 最小检索数量（质量保证）
         */
        private int minRetrieveK = 10;

        /**
         * 最大检索数量（成本控制）
         */
        private int maxRetrieveK = 50;
    }

    @Data
    public static class ScenarioStrategy {
        private String name;
        private String description;
        
        /**
         * 该场景的检索倍数
         */
        private double retrieveMultiplier;
        
        /**
         * 该场景的阈值降低
         */
        private double thresholdReduction;
        
        /**
         * 该场景是否启用 rerank
         */
        private boolean enableRerank;
        
        /**
         * 该场景的 rerank 输出比例
         */
        private double rerankOutputRatio;
    }

    @Data
    public static class DocumentTypeStrategy {
        /**
         * 文档类型特点
         */
        private String characteristic; // "structured", "complex", "simple"

        /**
         * 检索倍数（根据文档复杂度）
         */
        private double retrieveMultiplier;

        /**
         * 阈值降低（根据文档质量要求）
         */
        private double thresholdReduction;

        /**
         * 是否推荐 rerank
         */
        private boolean recommendRerank;

        /**
         * rerank 输出比例
         */
        private double rerankOutputRatio;

        /**
         * 检索边界控制
         */
        private int minRetrieveK;
        private int maxRetrieveK;
    }

    /**
     * 计算最优的宽进严出参数
     */
    public WideToTightParams calculateParams(WideToTightRequest request) {
        // 1. 获取基础策略
        DefaultStrategy defaultStrategy = this.defaultStrategy;
        
        // 2. 获取场景策略
        ScenarioStrategy scenarioStrategy = scenarios.get(request.getScenario());
        
        // 3. 获取文档类型策略
        DocumentTypeStrategy docStrategy = documentTypes.get(request.getDocumentType());
        
        // 4. 计算检索参数（宽进）
        RetrieveParams retrieveParams = calculateRetrieveParams(
            request, defaultStrategy, scenarioStrategy, docStrategy);
        
        // 5. 计算 rerank 参数（严出）
        RerankParams rerankParams = calculateRerankParams(
            request, defaultStrategy, scenarioStrategy, docStrategy);
        
        return WideToTightParams.builder()
            .retrieveParams(retrieveParams)
            .rerankParams(rerankParams)
            .strategy(getAppliedStrategy(scenarioStrategy, docStrategy))
            .build();
    }

    private RetrieveParams calculateRetrieveParams(WideToTightRequest request,
                                                 DefaultStrategy defaultStrategy,
                                                 ScenarioStrategy scenarioStrategy,
                                                 DocumentTypeStrategy docStrategy) {
        
        // 选择最合适的倍数
        double multiplier = defaultStrategy.getRetrieveMultiplier();
        if (scenarioStrategy != null) {
            multiplier = scenarioStrategy.getRetrieveMultiplier();
        } else if (docStrategy != null) {
            multiplier = docStrategy.getRetrieveMultiplier();
        }
        
        // 选择最合适的阈值降低
        double thresholdReduction = defaultStrategy.getThresholdReduction();
        if (scenarioStrategy != null) {
            thresholdReduction = scenarioStrategy.getThresholdReduction();
        } else if (docStrategy != null) {
            thresholdReduction = docStrategy.getThresholdReduction();
        }
        
        // 计算检索数量
        int retrieveK = (int) Math.ceil(request.getUserTopK() * multiplier);
        
        // 边界控制
        int minK = defaultStrategy.getMinRetrieveK();
        int maxK = defaultStrategy.getMaxRetrieveK();
        if (docStrategy != null) {
            minK = docStrategy.getMinRetrieveK();
            maxK = docStrategy.getMaxRetrieveK();
        }
        
        retrieveK = Math.max(retrieveK, minK);
        retrieveK = Math.min(retrieveK, maxK);
        
        // 计算相似度阈值
        double similarityThreshold = request.getUserSimilarityThreshold() * (1.0 - thresholdReduction);
        
        return RetrieveParams.builder()
            .retrieveK(retrieveK)
            .similarityThreshold(similarityThreshold)
            .multiplier(multiplier)
            .thresholdReduction(thresholdReduction)
            .build();
    }

    private RerankParams calculateRerankParams(WideToTightRequest request,
                                             DefaultStrategy defaultStrategy,
                                             ScenarioStrategy scenarioStrategy,
                                             DocumentTypeStrategy docStrategy) {
        
        // 判断是否启用 rerank
        boolean enableRerank = request.isUserEnableRerank();
        if (scenarioStrategy != null) {
            enableRerank = enableRerank && scenarioStrategy.isEnableRerank();
        } else if (docStrategy != null) {
            enableRerank = enableRerank && docStrategy.isRecommendRerank();
        }
        
        // 计算 rerank 输出数量
        double outputRatio = defaultStrategy.getRerankOutputRatio();
        if (scenarioStrategy != null) {
            outputRatio = scenarioStrategy.getRerankOutputRatio();
        } else if (docStrategy != null) {
            outputRatio = docStrategy.getRerankOutputRatio();
        }
        
        int rerankTopK = (int) Math.ceil(request.getUserTopK() * outputRatio);
        
        return RerankParams.builder()
            .enableRerank(enableRerank)
            .rerankTopK(rerankTopK)
            .outputRatio(outputRatio)
            .build();
    }

    private String getAppliedStrategy(ScenarioStrategy scenarioStrategy, DocumentTypeStrategy docStrategy) {
        if (scenarioStrategy != null) {
            return "scenario:" + scenarioStrategy.getName();
        } else if (docStrategy != null) {
            return "document-type:" + docStrategy.getCharacteristic();
        } else {
            return "default";
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class WideToTightRequest {
        private String scenario;           // 场景：qa, research, decision
        private String documentType;       // 文档类型：sop, knowledge, faq
        private int userTopK;              // 用户请求的 topK
        private double userSimilarityThreshold; // 用户请求的相似度阈值
        private boolean userEnableRerank;   // 用户是否启用 rerank
    }

    @lombok.Data
    @lombok.Builder
    public static class RetrieveParams {
        private int retrieveK;
        private double similarityThreshold;
        private double multiplier;
        private double thresholdReduction;
    }

    @lombok.Data
    @lombok.Builder
    public static class RerankParams {
        private boolean enableRerank;
        private int rerankTopK;
        private double outputRatio;
    }

    @lombok.Data
    @lombok.Builder
    public static class WideToTightParams {
        private RetrieveParams retrieveParams;
        private RerankParams rerankParams;
        private String strategy;
    }
}
