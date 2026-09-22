package com.pacvue.mcpgty.repository;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 工具调用审计记录
 * 注意：不存 arguments 原文，只存字段摘要（脱敏）
 */
@Entity
@Table(name = "audit_record")
public class AuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "caller_tenant")
    private String callerTenant;

    @Column(name = "alias")
    private String alias;

    @Column(name = "tool")
    private String tool;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "request_summary")
    private String requestSummary; // 字段名+长度，不存原文

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public AuditRecord() {}

    public AuditRecord(String traceId, String callerTenant, String alias, String tool,
                       String outcome, Long durationMs, String requestSummary, String errorCode) {
        this.traceId = traceId;
        this.callerTenant = callerTenant;
        this.alias = alias;
        this.tool = tool;
        this.outcome = outcome;
        this.durationMs = durationMs;
        this.requestSummary = requestSummary;
        this.errorCode = errorCode;
    }

    // getters
    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getCallerTenant() { return callerTenant; }
    public String getAlias() { return alias; }
    public String getTool() { return tool; }
    public String getOutcome() { return outcome; }
    public Long getDurationMs() { return durationMs; }
    public String getRequestSummary() { return requestSummary; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
}
