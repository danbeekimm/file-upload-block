package com.study.fileupload.upload;

import java.time.Instant;
import java.util.UUID;

/** GET /api/uploads 응답 항목 — 저장된 파일 하나. 순차 id는 노출하지 않고 public_id만 쓴다 (명세 7장). */
public record UploadListItem(
        UUID publicId,
        String fileName,        // 다운로드 이름 (재부여·변환 반영)
        String extension,
        long sizeBytes,
        String contentType,
        String disposition,     // INLINE | ATTACHMENT
        String trustLevel,      // TRUSTED | UNTRUSTED
        Instant createdAt
) {

    public static UploadListItem from(FileUploadRecord r) {
        String name = r.getDownloadName() != null ? r.getDownloadName() : r.getOriginalName();
        return new UploadListItem(r.getPublicId(), name, r.getExtension(), r.getSizeBytes(),
                r.getContentType(), r.getDisposition(), r.getTrustLevel(), r.getCreatedAt());
    }
}
