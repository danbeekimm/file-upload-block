package com.study.fileupload.policy;

import com.study.fileupload.common.Actor;
import com.study.fileupload.common.ApiException;
import com.study.fileupload.common.ExtensionNormalizer;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    public static final String DEFAULT_POLICY = "default";
    public static final int MAX_CUSTOM = 200;
    /** 고정 확장자의 화면 표시 순서 (요구사항 나열 순서) */
    public static final List<String> FIXED_ORDER = List.of("bat", "cmd", "com", "cpl", "exe", "scr", "js");

    private final UploadPolicyRepository policyRepository;
    private final ExtensionRuleRepository ruleRepository;
    private final PolicyChangeLogRepository changeLogRepository;
    private final EntityManager entityManager;

    public PolicyService(UploadPolicyRepository policyRepository,
                         ExtensionRuleRepository ruleRepository,
                         PolicyChangeLogRepository changeLogRepository,
                         EntityManager entityManager) {
        this.policyRepository = policyRepository;
        this.ruleRepository = ruleRepository;
        this.changeLogRepository = changeLogRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public UploadPolicy defaultPolicy() {
        return policyRepository.findByName(DEFAULT_POLICY)
                .orElseThrow(() -> new IllegalStateException("default 정책이 없습니다. schema.sql 시드를 확인하세요."));
    }

    /** 업로드 판정용 스냅샷: 정책 + 차단 목록 + 정책 버전을 한 번에 읽는다 (명세 2-2). */
    @Transactional(readOnly = true)
    public PolicySnapshot snapshot() {
        UploadPolicy policy = defaultPolicy();
        List<String> blocked = ruleRepository.findBlockedExtensions(policy.getId());
        long version = changeLogRepository.currentVersion(policy.getId());
        return new PolicySnapshot(policy, new HashSet<>(blocked), version);
    }

    @Transactional(readOnly = true)
    public PolicyView view() {
        UploadPolicy policy = defaultPolicy();
        Map<String, ExtensionRule> fixedByExt = ruleRepository
                .findByPolicyIdAndRuleTypeOrderByCreatedAtAscIdAsc(policy.getId(), ExtensionRule.TYPE_FIXED)
                .stream()
                .collect(java.util.stream.Collectors.toMap(ExtensionRule::getExtension, r -> r));
        List<PolicyView.FixedItem> fixed = FIXED_ORDER.stream()
                .map(fixedByExt::get)
                .filter(java.util.Objects::nonNull)
                .map(r -> new PolicyView.FixedItem(r.getExtension(), r.isBlocked(), r.getVersion()))
                .toList();
        List<PolicyView.CustomItem> custom = ruleRepository
                .findByPolicyIdAndRuleTypeOrderByCreatedAtAscIdAsc(policy.getId(), ExtensionRule.TYPE_CUSTOM)
                .stream()
                .map(r -> new PolicyView.CustomItem(r.getExtension()))
                .toList();
        return new PolicyView(fixed, custom, custom.size(), MAX_CUSTOM);
    }

    /**
     * 고정 확장자 토글 (명세 4-2, 4-3). 원하는 상태를 명시하는 멱등 API.
     * 클라이언트가 본 version과 다르면 409 — 동시 편집 감지.
     */
    @Transactional
    public PolicyView.FixedItem toggleFixed(String ext, boolean blocked, int clientVersion, Actor actor) {
        UploadPolicy policy = defaultPolicy();
        String normalized = ExtensionNormalizer.normalizeCustomInput(ext)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "고정 확장자가 아닙니다."));
        ExtensionRule rule = ruleRepository.findByPolicyIdAndExtension(policy.getId(), normalized)
                .filter(ExtensionRule::isFixed)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "고정 확장자가 아닙니다."));

        if (rule.getVersion() != clientVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "다른 사용자가 먼저 변경했습니다. 새로고침 후 다시 시도해 주세요.");
        }
        if (rule.isBlocked() != blocked) {
            rule.toggle(blocked);
            changeLogRepository.save(PolicyChangeLog.extensionRule(
                    policy.getId(), normalized, blocked ? "BLOCK" : "UNBLOCK",
                    "{\"blocked\":{\"before\":" + !blocked + ",\"after\":" + blocked + "}}",
                    actor.ip(), actor.userAgent()));
            log.info("fixed extension {} -> {} by {}", normalized, blocked ? "BLOCK" : "UNBLOCK", actor.ip());
        }
        return new PolicyView.FixedItem(rule.getExtension(), rule.isBlocked(), rule.getVersion());
    }

    /**
     * 커스텀 확장자 추가 (명세 4-1, 4-2).
     * 200개 제한의 "개수 조회 → 삽입" 경쟁은 pg_advisory_xact_lock으로 직렬화하고,
     * 중복·고정 충돌은 UNIQUE(policy_id, extension)이 DB 레벨에서 최종 방어한다.
     */
    @Transactional
    public CustomAddResult addCustom(String rawInput, Actor actor) {
        UploadPolicy policy = defaultPolicy();
        String normalized = ExtensionNormalizer.normalizeCustomInput(rawInput)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXTENSION",
                        "확장자는 영문 소문자와 숫자 1~20자만 가능합니다. (예: sh, php7)"));
        boolean corrected = !normalized.equals(rawInput);

        lockPolicy(policy.getId());

        Optional<ExtensionRule> existing = ruleRepository.findByPolicyIdAndExtension(policy.getId(), normalized);
        if (existing.isPresent()) {
            if (existing.get().isFixed()) {
                throw new ApiException(HttpStatus.CONFLICT, "FIXED_CONFLICT",
                        normalized + "는 고정 확장자입니다. 위 체크박스로 차단해 주세요.");
            }
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_EXTENSION", "이미 등록된 확장자입니다.");
        }
        long count = ruleRepository.countByPolicyIdAndRuleType(policy.getId(), ExtensionRule.TYPE_CUSTOM);
        if (count >= MAX_CUSTOM) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOM_LIMIT_EXCEEDED",
                    "삭제 후 추가할 수 있습니다.", Map.of("current", count, "max", MAX_CUSTOM));
        }
        try {
            ruleRepository.save(ExtensionRule.custom(policy.getId(), normalized));
            ruleRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // advisory lock이 있어 정상 경로에서는 오지 않지만, DB 제약이 최종 방어선 (명세 1장)
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_EXTENSION", "이미 등록된 확장자입니다.");
        }
        changeLogRepository.save(PolicyChangeLog.extensionRule(
                policy.getId(), normalized, "ADD", null, actor.ip(), actor.userAgent()));
        log.info("custom extension added: {} by {}", normalized, actor.ip());
        return new CustomAddResult(normalized, corrected,
                (int) count + 1, MAX_CUSTOM);
    }

    /** 커스텀 확장자 삭제. 이미 없어도 204 — 원하는 상태이므로 성공 (명세 4-2). */
    @Transactional
    public void deleteCustom(String rawExt, Actor actor) {
        UploadPolicy policy = defaultPolicy();
        Optional<String> normalized = ExtensionNormalizer.normalizeCustomInput(rawExt);
        if (normalized.isEmpty()) {
            return;   // 형식에 맞지 않는 확장자는 존재할 수 없으므로 이미 원하는 상태
        }
        ruleRepository.findByPolicyIdAndExtension(policy.getId(), normalized.get())
                .filter(rule -> !rule.isFixed())   // 고정 확장자는 삭제 대상이 아님
                .ifPresent(rule -> {
                    ruleRepository.delete(rule);
                    changeLogRepository.save(PolicyChangeLog.extensionRule(
                            policy.getId(), rule.getExtension(), "DELETE", null, actor.ip(), actor.userAgent()));
                    log.info("custom extension deleted: {} by {}", rule.getExtension(), actor.ip());
                });
    }

    private void lockPolicy(Long policyId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:id)")
                .setParameter("id", policyId)
                .getSingleResult();
    }

    public record CustomAddResult(String extension, boolean corrected, int count, int max) {
    }
}
