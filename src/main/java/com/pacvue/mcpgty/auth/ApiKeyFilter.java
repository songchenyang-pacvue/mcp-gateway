package com.pacvue.mcpgty.auth;

import com.pacvue.mcpgty.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * X-Api-Key 认证过滤器
 * 校验请求头里的 ApiKey，解析成 CallerContext 放入 ThreadLocal
 * 校验失败直接 403，不触发 OAuth 流程
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final SecurityProperties security;

    public ApiKeyFilter(SecurityProperties security) {
        this.security = security;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 只拦截 /mcp 端点
        if (!request.getRequestURI().startsWith("/mcp")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 认证未开启直接放行（本地调试用）
        if (!security.enabled()) {
            CallerContextHolder.set(new CallerContext("local", "debug", Set.of("*")));
            try {
                filterChain.doFilter(request, response);
            } finally {
                CallerContextHolder.clear();
            }
            return;
        }

        // 取 ApiKey
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"errorCode\":\"API_KEY_MISSING\",\"retryable\":false,\"clientHint\":\"请在请求头添加 X-Api-Key\"}");
            return;
        }

        // 查 Key 表
        String tenantId = security.apiKeys().get(apiKey);
        if (tenantId == null) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"errorCode\":\"API_KEY_INVALID\",\"retryable\":false,\"clientHint\":\"ApiKey 无效\"}");
            return;
        }

        // 构造身份上下文
        CallerContext ctx = new CallerContext(tenantId, "tenant-" + tenantId, Set.of("*"));
        CallerContextHolder.set(ctx);
        System.out.println("鉴权通过: tenant=" + tenantId + ", ip=" + request.getRemoteAddr());

        try {
              filterChain.doFilter(request, response);
        } finally {
            CallerContextHolder.clear(); // 必须清理，防止线程池泄漏
        }
    }
}
