package com.agentplatform.gateway.audit;

import com.agentplatform.common.model.CallerIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API 统一审计切面
 * 对 /api/v1 下的 REST 能力（vectors/rag/chunking/prompt/memory 等）进行审计
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class RestApiAuditAspect {

    private final AuditLogService auditLogService;

    /**
     * 切入点：匹配 /api/v1 下的所有 Controller
     */
    @Pointcut("execution(* com.agentplatform.gateway.vector.VectorController.*(..)) || " +
              "execution(* com.agentplatform.gateway.rag.*.*(..)) || " +
              "execution(* com.agentplatform.gateway.prompt.PromptController.*(..)) || " +
              "execution(* com.agentplatform.gateway.memory.MemoryController.*(..))")
    public void restApiEndpoints() {}

    @Around("restApiEndpoints()")
    public Object auditRestCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String action = extractAction(method);
        String endpoint = extractEndpoint(method);
        
        // 提取 CallerIdentity
        CallerIdentity identity = extractCallerIdentity(joinPoint);
        
        // 提取请求参数（脱敏）
        Map<String, Object> requestParams = extractRequestParams(joinPoint, signature);
        
        try {
            Object result = joinPoint.proceed();
            long latencyMs = System.currentTimeMillis() - startTime;
            
            // 记录成功审计
            if (identity != null) {
                auditLogService.log(
                    identity,
                    action,
                    endpoint,
                    "SUCCESS",
                    Map.of(
                        "latency_ms", latencyMs,
                        "request_params", sanitizeParams(requestParams)
                    )
                );
            }
            
            log.debug("REST API audit: action={}, endpoint={}, latency={}ms, status=SUCCESS",
                action, endpoint, latencyMs);
            
            return result;
            
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            
            // 记录失败审计
            if (identity != null) {
                auditLogService.log(
                    identity,
                    action,
                    endpoint,
                    "ERROR:" + e.getClass().getSimpleName(),
                    Map.of(
                        "latency_ms", latencyMs,
                        "error_message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                        "request_params", sanitizeParams(requestParams)
                    )
                );
            }
            
            log.debug("REST API audit: action={}, endpoint={}, latency={}ms, status=ERROR, error={}",
                action, endpoint, latencyMs, e.getMessage());
            
            throw e;
        }
    }

    /**
     * 提取 HTTP 方法作为 action
     */
    private String extractAction(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return "GET";
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            return "POST";
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            return "PUT";
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            return "DELETE";
        } else if (method.isAnnotationPresent(PatchMapping.class)) {
            return "PATCH";
        }
        return "UNKNOWN";
    }

    /**
     * 提取端点路径
     */
    private String extractEndpoint(Method method) {
        Class<?> declaringClass = method.getDeclaringClass();
        String basePath = "";
        
        if (declaringClass.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping classMapping = declaringClass.getAnnotation(RequestMapping.class);
            if (classMapping.value().length > 0) {
                basePath = classMapping.value()[0];
            }
        }
        
        String methodPath = "";
        if (method.isAnnotationPresent(GetMapping.class)) {
            String[] paths = method.getAnnotation(GetMapping.class).value();
            methodPath = paths.length > 0 ? paths[0] : "";
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            String[] paths = method.getAnnotation(PostMapping.class).value();
            methodPath = paths.length > 0 ? paths[0] : "";
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            String[] paths = method.getAnnotation(PutMapping.class).value();
            methodPath = paths.length > 0 ? paths[0] : "";
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            String[] paths = method.getAnnotation(DeleteMapping.class).value();
            methodPath = paths.length > 0 ? paths[0] : "";
        } else if (method.isAnnotationPresent(PatchMapping.class)) {
            String[] paths = method.getAnnotation(PatchMapping.class).value();
            methodPath = paths.length > 0 ? paths[0] : "";
        }
        
        return basePath + methodPath;
    }

    /**
     * 从方法参数中提取 CallerIdentity
     */
    private CallerIdentity extractCallerIdentity(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof CallerIdentity) {
                return (CallerIdentity) arg;
            }
        }
        return null;
    }

    /**
     * 提取请求参数
     */
    private Map<String, Object> extractRequestParams(ProceedingJoinPoint joinPoint, MethodSignature signature) {
        Map<String, Object> params = new HashMap<>();
        Object[] args = joinPoint.getArgs();
        Parameter[] parameters = signature.getMethod().getParameters();
        
        for (int i = 0; i < args.length && i < parameters.length; i++) {
            Parameter param = parameters[i];
            Object arg = args[i];
            
            // 跳过 CallerIdentity 和 null
            if (arg == null || arg instanceof CallerIdentity) {
                continue;
            }
            
            // 提取 PathVariable 和 RequestParam
            if (param.isAnnotationPresent(PathVariable.class)) {
                PathVariable pv = param.getAnnotation(PathVariable.class);
                String name = pv.value().isEmpty() ? param.getName() : pv.value();
                params.put(name, arg.toString());
            } else if (param.isAnnotationPresent(RequestParam.class)) {
                RequestParam rp = param.getAnnotation(RequestParam.class);
                String name = rp.value().isEmpty() ? param.getName() : rp.value();
                params.put(name, arg.toString());
            } else if (param.isAnnotationPresent(RequestBody.class)) {
                // RequestBody 只记录类型，不记录完整内容
                params.put("body_type", arg.getClass().getSimpleName());
            }
        }
        
        return params;
    }

    /**
     * 参数脱敏
     */
    private Map<String, Object> sanitizeParams(Map<String, Object> params) {
        Map<String, Object> sanitized = new HashMap<>(params);
        
        // 脱敏敏感字段
        for (String key : sanitized.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("password") || 
                lowerKey.contains("secret") || 
                lowerKey.contains("token") ||
                lowerKey.contains("key")) {
                sanitized.put(key, "***REDACTED***");
            }
        }
        
        return sanitized;
    }
}
