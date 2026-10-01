package com.study.fileupload.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 차단 목록 한 행. FIXED는 blocked만 토글, CUSTOM은 행 존재 = 차단.
 * version은 고정 토글의 낙관적 락 (명세 4-3) — 비교·증가를 서비스가 직접 수행하므로
 * JPA @Version을 쓰지 않는다 (클라이언트가 본 버전과 비교하는 의미이기 때문).
 */
@Entity
@Table(name = "extension_rule")
public class ExtensionRule {

    public static final String TYPE_FIXED = "FIXED";
    public static final String TYPE_CUSTOM = "CUSTOM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(nullable = false, length = 20)
    private String extension;

    @Column(name = "rule_type", nullable = false, length = 10)
    private String ruleType;

    @Column(nullable = false)
    private boolean blocked;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExtensionRule() {
    }

    public static ExtensionRule custom(Long policyId, String extension) {
        ExtensionRule rule = new ExtensionRule();
        rule.policyId = policyId;
        rule.extension = extension;
        rule.ruleType = TYPE_CUSTOM;
        rule.blocked = true;   // 커스텀은 항상 차단 (ck_extension_rule_custom_blocked)
        return rule;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    public void toggle(boolean blocked) {
        this.blocked = blocked;
        this.version++;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getExtension() {
        return extension;
    }

    public String getRuleType() {
        return ruleType;
    }

    public boolean isFixed() {
        return TYPE_FIXED.equals(ruleType);
    }

    public boolean isBlocked() {
        return blocked;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
