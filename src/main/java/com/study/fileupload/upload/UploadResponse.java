package com.study.fileupload.upload;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** POST /api/uploads 응답 — 파일별 부분 성공 (명세 2-2, 15장) */
public record UploadResponse(UUID requestId, List<FileResult> results) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileResult(
            String fileName,
            String status,          // STORED | REJECTED
            UUID publicId,          // STORED일 때만
            String disposition,     // STORED일 때만 (INLINE | ATTACHMENT)
            String reasonCode,      // REJECTED일 때만
            String message,         // REJECTED일 때만 (명확한 사유)
            List<String> notices    // 차단이 아닌 안내 (이름 재부여, PNG 변환 등)
    ) {

        public static FileResult stored(String fileName, UUID publicId, String disposition, List<String> notices) {
            return new FileResult(fileName, "STORED", publicId, disposition, null, null,
                    notices.isEmpty() ? null : notices);
        }

        public static FileResult rejected(String fileName, String reasonCode, String message) {
            return new FileResult(fileName, "REJECTED", null, null, reasonCode, message, null);
        }
    }
}
