package com.agentplatform.gateway.planner.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略注册表 - 管理所有可用的执行策略
 */
@Component
@Slf4j
public class StrategyRegistry {
    
    private final Map<String, ExecutionStrategy> strategies = new ConcurrentHashMap<>();
    
    /**
     * 注册策略
     */
    public void register(ExecutionStrategy strategy) {
        strategies.put(strategy.name(), strategy);
        log.info("Registered execution strategy: {}", strategy.name());
    }
    
    /**
     * 获取策略
     */
    public Optional<ExecutionStrategy> get(String name) {
        return Optional.ofNullable(strategies.get(name));
    }
    
    /**
     * 获取策略，不存在则抛异常
     */
    public ExecutionStrategy getOrThrow(String name) {
        return get(name).orElseThrow(() -> 
            new IllegalArgumentException("Unknown strategy: " + name + ". Available: " + strategies.keySet()));
    }
    
    /**
     * 获取默认策略
     */
    public ExecutionStrategy getDefault() {
        return strategies.getOrDefault("plan_then_execute", 
            strategies.values().stream().findFirst().orElseThrow(() -> 
                new IllegalStateException("No strategies registered")));
    }
    
    /**
     * 列出所有策略
     */
    public List<String> listStrategies() {
        return List.copyOf(strategies.keySet());
    }
    
    /**
     * 检查策略是否存在
     */
    public boolean exists(String name) {
        return strategies.containsKey(name);
    }
}
