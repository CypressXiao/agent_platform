package com.agentplatform.gateway.planner;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.router.ToolAggregator;
import com.agentplatform.gateway.planner.model.Plan;
import com.agentplatform.gateway.planner.repository.PlanRepository;
import com.agentplatform.gateway.planner.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Planning engine - 策略调度器
 * 
 * 支持多种执行策略：
 * - plan_then_execute: 先规划后执行
 * - react: ReAct 循环
 * - human_in_loop: 关键步骤需人工审批
 * 
 * SDK 用户可以通过 StrategyRegistry 注册自定义策略
 */
@Service
@ConditionalOnProperty(name = "agent-platform.planner.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PlanningEngine {

    private final PlanRepository planRepo;
    private final ToolAggregator toolAggregator;
    private final StrategyRegistry strategyRegistry;

    /**
     * 使用默认策略创建并执行计划
     */
    public Plan createAndExecute(CallerIdentity identity, String goal, Map<String, Object> context) {
        return createAndExecute(identity, goal, context, null);
    }

    /**
     * 使用指定策略创建并执行计划
     * 
     * @param identity 调用方身份
     * @param goal 目标描述
     * @param context 初始上下文
     * @param strategyName 策略名称（null 使用默认策略）
     */
    public Plan createAndExecute(CallerIdentity identity, String goal, Map<String, Object> context, String strategyName) {
        ExecutionStrategy strategy = strategyName != null 
            ? strategyRegistry.getOrThrow(strategyName)
            : strategyRegistry.getDefault();

        return executeWithStrategy(identity, goal, context, strategy);
    }

    /**
     * 使用指定策略执行
     */
    public Plan executeWithStrategy(CallerIdentity identity, String goal, Map<String, Object> context, 
                                     ExecutionStrategy strategy) {
        // 构建执行上下文
        List<String> toolNames = toolAggregator.listTools(identity).stream()
            .map(ToolAggregator.ToolView::name)
            .toList();

        PlanContext planContext = PlanContext.builder()
            .goal(goal)
            .state(context != null ? new HashMap<>(context) : new HashMap<>())
            .availableTools(toolNames)
            .build();

        // 规划阶段
        List<Map<String, Object>> steps = strategy.plan(identity, goal, planContext);

        // 创建 Plan 记录
        Plan plan = Plan.builder()
            .planId(UUID.randomUUID().toString())
            .actorTid(identity.getTenantId())
            .goal(goal)
            .steps(new ArrayList<>(steps))
            .context(context)
            .status("EXECUTING")
            .strategyType(strategy.name())
            .build();
        plan = planRepo.save(plan);

        // 执行阶段
        plan = executeSteps(identity, plan, strategy, planContext);

        return plan;
    }

    /**
     * 继续执行已暂停的计划（用于 Human-in-the-Loop 审批后继续）
     */
    public Plan resumePlan(CallerIdentity identity, String planId, String approvalStatus) {
        Plan plan = planRepo.findById(planId)
            .orElseThrow(() -> new McpException(McpErrorCode.PLAN_NOT_FOUND, "Plan not found: " + planId));

        if (!identity.getTenantId().equals(plan.getActorTid())) {
            throw new McpException(McpErrorCode.FORBIDDEN_POLICY, "Not authorized to execute this plan");
        }

        if (!"WAITING_APPROVAL".equals(plan.getStatus())) {
            throw new McpException(McpErrorCode.BAD_REQUEST, "Plan is not waiting for approval");
        }

        ExecutionStrategy strategy = strategyRegistry.getOrThrow(plan.getStrategyType());

        // 重建上下文
        List<String> toolNames = toolAggregator.listTools(identity).stream()
            .map(ToolAggregator.ToolView::name)
            .toList();

        PlanContext planContext = PlanContext.builder()
            .goal(plan.getGoal())
            .state(plan.getContext() != null ? new HashMap<>(plan.getContext()) : new HashMap<>())
            .availableTools(toolNames)
            .approvalStatus(approvalStatus)
            .build();

        plan.setStatus("EXECUTING");
        plan = planRepo.save(plan);

        return executeSteps(identity, plan, strategy, planContext);
    }

    /**
     * 执行步骤
     */
    private Plan executeSteps(CallerIdentity identity, Plan plan, ExecutionStrategy strategy, PlanContext context) {
        List<Map<String, Object>> steps = plan.getSteps();

        while (context.getCurrentStepIndex() < steps.size()) {
            int idx = context.getCurrentStepIndex();
            Map<String, Object> step = new HashMap<>(steps.get(idx));

            // 执行步骤
            StepResult result = strategy.executeStep(identity, step, context);
            context.addHistory(result);

            // 更新步骤状态
            step.put("status", result.isSuccess() ? "COMPLETED" : "FAILED");
            step.put("result", result.getOutput());
            step.put("latency_ms", result.getLatencyMs());
            if (!result.isSuccess()) {
                step.put("error", result.getError());
            }
            steps.set(idx, step);
            plan.setSteps(steps);

            // 决定下一步动作
            NextAction nextAction = strategy.onStepComplete(step, result, context);

            switch (nextAction) {
                case CONTINUE:
                    // 继续下一步
                    break;

                case REPLAN:
                    // 重新规划（ReAct 模式）
                    if (strategy.supportsReplan() && strategy instanceof ReActLoopStrategy reactStrategy) {
                        Map<String, Object> nextStep = reactStrategy.reasonNextStep(identity, plan.getGoal(), context);
                        steps.add(nextStep);
                        plan.setSteps(steps);
                    }
                    break;

                case WAIT_APPROVAL:
                    // 暂停等待审批
                    plan.setStatus("WAITING_APPROVAL");
                    plan = planRepo.save(plan);
                    return plan;

                case END:
                    // 正常结束 - 兜底保存记忆（用户可覆盖 saveToMemory 自定义）
                    saveMemoryIfSupported(strategy, identity, plan.getGoal(), context, true);
                    plan.setStatus("COMPLETED");
                    plan.setCompletedAt(Instant.now());
                    return planRepo.save(plan);

                case FAILED:
                    // 执行失败 - 兜底保存记忆（记录失败经验）
                    saveMemoryIfSupported(strategy, identity, plan.getGoal(), context, false);
                    plan.setStatus("FAILED");
                    plan.setCompletedAt(Instant.now());
                    return planRepo.save(plan);
            }

            planRepo.save(plan);
        }

        // 所有步骤执行完成
        plan.setStatus("COMPLETED");
        plan.setCompletedAt(Instant.now());
        return planRepo.save(plan);
    }

    /**
     * 兜底保存记忆 - 只有开启记忆时才调用
     * 用户需要自己实现 saveToMemory 方法
     */
    private void saveMemoryIfSupported(ExecutionStrategy strategy, CallerIdentity identity, 
                                        String goal, PlanContext context, boolean success) {
        // 只有开启记忆时才保存
        if (!context.isMemoryEnabled()) {
            return;
        }
        
        try {
            if (strategy instanceof BaseExecutionStrategy baseStrategy) {
                baseStrategy.saveToMemory(identity, goal, context, success);
            }
        } catch (Exception e) {
            log.warn("Failed to save memory (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * 获取可用策略列表
     */
    public List<String> listStrategies() {
        return strategyRegistry.listStrategies();
    }

    /**
     * 查询计划
     */
    public Plan getPlan(String planId) {
        return planRepo.findById(planId)
            .orElseThrow(() -> new McpException(McpErrorCode.PLAN_NOT_FOUND, "Plan not found: " + planId));
    }
}
