package com.agentplatform.gateway.rag;

import com.agentplatform.common.model.CallerIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 服务
 * 统一的 RAG 查询入口，根据请求中的 mode 选择对应的策略执行
 */
@Service
@Slf4j
public class RagService {

    private final Map<RagMode, RagStrategy> strategies;
    private final RagStrategy defaultStrategy;

    public RagService(List<RagStrategy> strategyList) {
        this.strategies = strategyList.stream()
            .collect(Collectors.toMap(RagStrategy::supportedMode, Function.identity()));
        
        // 默认使用 Advanced RAG，如果不可用则使用 Naive RAG
        this.defaultStrategy = strategies.getOrDefault(RagMode.ADVANCED, 
            strategies.get(RagMode.NAIVE));
        
        log.info("Initialized RAG service with strategies: {}", strategies.keySet());
    }

    /**
     * 执行 RAG 查询
     *
     * @param identity 调用者身份
     * @param request RAG 请求
     * @return RAG 响应
     */
    public RagResponse query(CallerIdentity identity, RagRequest request) {
        RagMode mode = request.getMode();
        RagStrategy strategy = strategies.get(mode);

        if (strategy == null) {
            log.warn("RAG mode {} not available, falling back to default", mode);
            strategy = defaultStrategy;
            
            if (strategy == null) {
                return RagResponse.builder()
                    .success(false)
                    .answer("RAG 服务不可用，请检查配置。")
                    .build();
            }
        }

        log.info("Executing RAG query with mode={}, collection={}", 
            strategy.supportedMode(), request.getCollection());

        return strategy.execute(identity, request);
    }

    /**
     * 检查指定模式是否可用
     */
    public boolean isModeAvailable(RagMode mode) {
        return strategies.containsKey(mode);
    }

    /**
     * 获取可用的 RAG 模式列表
     */
    public List<RagMode> getAvailableModes() {
        return List.copyOf(strategies.keySet());
    }
}
