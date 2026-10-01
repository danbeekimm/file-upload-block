package com.study.fileupload.policy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UploadPolicyRepository extends JpaRepository<UploadPolicy, Long> {

    Optional<UploadPolicy> findByName(String name);
}

interface ExtensionRuleRepository extends JpaRepository<ExtensionRule, Long> {

    List<ExtensionRule> findByPolicyIdAndRuleTypeOrderByCreatedAtAscIdAsc(Long policyId, String ruleType);

    Optional<ExtensionRule> findByPolicyIdAndExtension(Long policyId, String extension);

    long countByPolicyIdAndRuleType(Long policyId, String ruleType);

    /** 판정용 차단 스냅샷: 체크된 고정 + 커스텀 전체 (커스텀은 항상 blocked) */
    @Query("select r.extension from ExtensionRule r where r.policyId = :policyId and r.blocked = true")
    List<String> findBlockedExtensions(@Param("policyId") Long policyId);
}

interface PolicyChangeLogRepository extends JpaRepository<PolicyChangeLog, Long> {

    /** 정책 버전 = MAX(id). 변경이 없으면 0 (명세 4-4) */
    @Query("select coalesce(max(l.id), 0) from PolicyChangeLog l where l.policyId = :policyId")
    long currentVersion(@Param("policyId") Long policyId);
}
