package com.pacvue.mcpgty.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {
    List<AuditRecord> findByTraceId(String traceId);
    List<AuditRecord> findTop10ByOrderByCreatedAtDesc();
}
