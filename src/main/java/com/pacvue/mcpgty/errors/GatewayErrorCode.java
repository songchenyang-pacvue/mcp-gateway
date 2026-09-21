package com.pacvue.mcpgty.errors;

public enum GatewayErrorCode {
    UPSTREAM_NOT_FOUND(false, "重新调 gw__list_servers"),
    TOOL_NOT_FOUND(false, "重新调 gw__list_tools"),
    UPSTREAM_TIMEOUT(true, "可重试一次，再失败放弃"),
    UPSTREAM_UNAUTHORIZED(false, "不要重试，提示人工处理"),
    TOOL_FORBIDDEN(false, "不要重试"),
    INVALID_ARGUMENTS(true, "按报错修正后重试");

    private final boolean retryable;
    private final String clientHint;

    GatewayErrorCode(boolean retryable, String clientHint) {
        this.retryable = retryable;
        this.clientHint = clientHint;
    }

    public boolean isRetryable() { return retryable; }
    public String getClientHint() { return clientHint; }
}
