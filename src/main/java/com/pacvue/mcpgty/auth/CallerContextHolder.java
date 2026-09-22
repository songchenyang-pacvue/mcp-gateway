package com.pacvue.mcpgty.auth;

/**
 * ThreadLocal 持有当前请求的身份上下文
 * 过滤器写入，业务代码读取，finally 清理防止泄漏
 */
public final class CallerContextHolder {

    private static final ThreadLocal<CallerContext> HOLDER = new ThreadLocal<>();

    private CallerContextHolder() {}

    public static void set(CallerContext ctx) {
        HOLDER.set(ctx);
    }

    public static CallerContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
