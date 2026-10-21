package com.admin.common.aop;


import cn.hutool.core.util.ArrayUtil;
import com.admin.common.utils.JwtUtil;
import com.alibaba.fastjson2.JSON;
import com.admin.common.utils.HttpContextUtils;
import com.admin.common.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.CodeSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@Aspect
@Slf4j
public class LogAspect {

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "cfapitoken", "ddnstoken", "secret", "password", "pwd", "currentpassword",
            "newpassword", "confirmpassword", "sshpassword", "sshprivatekey", "sshpassphrase",
            "terminalticket", "ticket", "token", "authorization", "apikey", "accesstoken",
            "refreshtoken", "privatekey", "passphrase", "credential", "credentials");

    @Pointcut("@annotation(com.admin.common.aop.LogAnnotation)")
    public void pt() {

    }

    /**
     * 返回后通知（@AfterReturning）：在某连接点（joinpoint）
     * 正常完成后执行的通知：例如，一个方法没有抛出任何异常，正常返回
     * 方法执行完毕之后
     * 注意在这里不能使用ProceedingJoinPoint
     * 不然会报错ProceedingJoinPoint is only supported for around advice
     * crmAspect()指向需要控制的方法
     * returning  注解返回值
     *
     * @param joinPoint
     * @param returnValue 返回值
     * @throws Exception
     */
    @AfterReturning(value = "pt()", returning = "returnValue")
    public void log(JoinPoint joinPoint, Object returnValue) throws Throwable {
        // 获取请求信息
        HttpServletRequest request = HttpContextUtils.getHttpServletRequest();
        
        // 获取请求方法类型（POST/GET等）
        String requestMethod = request.getMethod();
        
        // 获取用户ID
        String authorization = request.getHeader("Authorization") + "";
        Object user_id = "未登录"; // 请求用户的id
        if (!authorization.equals("null")) {
            try { user_id = JwtUtil.getUserIdFromToken(authorization); }
            catch (RuntimeException ignored) { /* An invalid public-endpoint header has no audit identity. */ }
        }
        
        // 获取请求IP
        String ipAddr = IpUtils.getIpAddr(request);
        
        // 获取方法签名信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        
        // 获取控制器方法名
        String className = joinPoint.getTarget().getClass().getName();
        String methodName = signature.getName();
        String controllerMethod = className + "." + methodName;
        

        // 获取请求参数
        String requestParams = getRequestParams(joinPoint);
        
        // 获取返回参数
        String responseParams = returnValue != null ? redactSensitiveValues(JSON.toJSONString(returnValue)) : "无返回值";
        
        // 合并为一条完整的日志信息
        String logMessage = String.format(
            "【请求日志】用户ID:[%s], IP地址:[%s], 请求方式:[%s], 控制器方法:[%s], 请求参数:[%s], 返回参数:[%s]", user_id, ipAddr, requestMethod, controllerMethod, requestParams, responseParams
        );
        
        // 打印单条完整日志
        log.info(logMessage);
    }


    /**
     * 抛出异常后通知（@AfterThrowing）：方法抛出异常退出时执行的通知
     * 注意在这里不能使用ProceedingJoinPoint
     * 不然会报错ProceedingJoinPoint is only supported for around advice
     * throwing注解为错误信息
     *
     * @param joinPoint
     * @param ex
     */
    @AfterThrowing(value = "pt()", throwing = "ex")
    public void recordLog(JoinPoint joinPoint, Exception ex) {
        try {
            // 获取请求信息
            HttpServletRequest request = HttpContextUtils.getHttpServletRequest();
            
            // 获取请求方法类型（POST/GET等）
            String requestMethod = request.getMethod();
            
            // 获取用户ID
            String authorization = request.getHeader("Authorization") + "";
            Object user_id = "未登录"; // 请求用户的id
            if (!authorization.equals("null")) {
                try { user_id = JwtUtil.getUserIdFromToken(authorization); }
                catch (RuntimeException ignored) { /* Keep malformed headers out of audit failures. */ }
            }
            
            // 获取请求IP
            String ipAddr = IpUtils.getIpAddr(request);
            
            // 获取方法签名信息
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            
            // 获取控制器方法名
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = signature.getName();
            String controllerMethod = className + "." + methodName;
            

            
            // 获取请求参数
            String requestParams = getRequestParams(joinPoint);
            
            // 获取异常信息
            // Exception messages and Throwable rendering can contain unredacted
            // input or connection credentials. Keep only the failure type here.
            String exceptionMsg = ex != null ? ex.getClass().getName() : "未知异常";
            
            // 合并为一条完整的异常日志信息
            String errorMessage = String.format(
                "【异常日志】用户ID:[%s], IP地址:[%s], 请求方式:[%s], 控制器方法:[%s], 请求参数:[%s], 异常信息:[%s]", user_id, ipAddr, requestMethod, controllerMethod, requestParams, exceptionMsg
            );
            
            // 打印单条完整异常日志
            log.info(errorMessage);
        } catch (Exception e) {
            log.info("记录异常日志时出错: {}", e.getClass().getName());
        }
    }
    
    /**
     * 获取请求参数
     */
    private String getRequestParams(JoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args.length == 0) {
                return "无参数";
            } else if (args[0] != null && args[0].toString().contains("SecurityContextHolderAwareRequestWrapper")) {
                return redactSensitiveValues(JSON.toJSONString(ArrayUtil.remove(args, 0)));
            } else {
                // 检查是否只有一个参数且已经是JSON字符串格式
                if (args.length == 1 && args[0] != null) {
                    // 如果参数本身就是字符串且是JSON格式，直接返回
                    if (args[0] instanceof String && ((String) args[0]).startsWith("{") && ((String) args[0]).endsWith("}")) {
                        return redactSensitiveValues((String) args[0]);
                    }
                    
                    // 如果参数是普通对象，直接序列化
                    try {
                        return redactSensitiveValues(JSON.toJSONString(args[0]));
                    } catch (Exception e) {
                        // 如果序列化失败，再尝试使用参数名映射
                        Map<String, Object> map = new HashMap<>();
                        String[] names = ((CodeSignature) joinPoint.getSignature()).getParameterNames();
                        if (names != null) {
                            map.put(names[0], args[0]);
                            return redactSensitiveValues(JSON.toJSONString(map));
                        }
                        return redactSensitiveValues(JSON.toJSONString(args[0]));
                    }
                } else {
                    // 多个参数时，使用参数名映射
                    Map<String, Object> map = new HashMap<>();
                    String[] names = ((CodeSignature) joinPoint.getSignature()).getParameterNames();
                    if (names != null) {
                        for (int i = 0; i < names.length; i++) {
                            map.put(names[i], args[i]);
                        }
                    }
                    return redactSensitiveValues(JSON.toJSONString(map));
                }
            }
        } catch (Exception e) {
            return "获取参数失败";
        }
    }

    private String redactSensitiveValues(String value) {
        try {
            return JSON.toJSONString(redactValue(JSON.parse(value), 0));
        } catch (Exception ignored) {
            // Never return the original when serialization is incomplete.
            return "[已省略无法脱敏的参数]";
        }
    }

    private Object redactValue(Object value, int depth) {
        if (depth > 32) return "[已省略深层参数]";
        if (value instanceof Map<?, ?> entries) {
            Map<String, Object> result = new LinkedHashMap<>();
            entries.forEach((key, item) -> {
                String name = String.valueOf(key);
                String normalized = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                result.put(name, SENSITIVE_FIELDS.contains(normalized) ? "***" : redactValue(item, depth + 1));
            });
            return result;
        }
        if (value instanceof Collection<?> items) {
            Collection<Object> result = new ArrayList<>();
            items.forEach(item -> result.add(redactValue(item, depth + 1)));
            return result;
        }
        if (value instanceof String text && (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("["))) {
            try { return JSON.toJSONString(redactValue(JSON.parse(text), depth + 1)); }
            catch (Exception ignored) { return "[已省略无法脱敏的参数]"; }
        }
        return value;
    }
}
