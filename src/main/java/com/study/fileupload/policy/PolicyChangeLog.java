package com.study.fileupload.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 정책 변경 이력 (append-only). id가 곧 정책 버전 (명세 4-4). */
@Entity
@Table(name = "policy_change_log")
public class PolicyChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(nullable = false, length = 20)
    private String target;

    @Column(length = 20)
    private String extension;

    @Column(nullable = false, length = 10)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Column(name = "actor_ip", length = 45)
    private String actorIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected PolicyChangeLog() {
    }

    public static PolicyChangeLog extensionRule(Long policyId, String extension, String action,
                                                String detail, String actorIp, String userAgent) {
        PolicyChangeLog log = new PolicyChangeLog();
        log.policyId = policyId;
        log.target = "EXTENSION_RULE";
        log.extension = extension;
        log.action = action;
        log.detail = detail;
        log.actorIp = actorIp;
        log.userAgent = userAgent;
        return log;
    }

    @PrePersist
    void prePersist() {
        changedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }
}
