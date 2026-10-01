package com.study.fileupload.policy;

import java.util.Set;

/**
 * 업로드 판정 시작 시 한 번에 읽는 정책 스냅샷 (명세 2-2).
 * 요청 전체가 같은 스냅샷으로 판정되어, 판정 중 정책이 바뀌어도 일관된 결과를 낸다.
 */
public record PolicySnapshot(UploadPolicy policy, Set<String> blockedExtensions, long policyVersion) {

    public boolean isBlocked(String normalizedExtension) {
        return blockedExtensions.contains(normalizedExtension);
    }
}
