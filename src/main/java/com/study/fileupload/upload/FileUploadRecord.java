package com.study.fileupload.upload;

import com.study.fileupload.common.ReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 파일별 판정 기록 (file_upload). 허용·거부 모두 남긴다 (명세 11장). */
@Entity
@Table(name = "file_upload")
public class FileUploadRecord {

    public static final String STATUS_STORED = "STORED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_DELETED = "DELETED";

    public static final String DISPOSITION_INLINE = "INLINE";
    public static final String DISPOSITION_ATTACHMENT = "ATTACHMENT";

    public static final String TRUST_TRUSTED = "TRUSTED";
    public static final String TRUST_UNTRUSTED = "UNTRUSTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "policy_version", nullable = false)
    private long policyVersion;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "reject_reason", length = 30)
    private String rejectReason;

    @Column(name = "trust_level", length = 10)
    private String trustLevel;

    @Column(name = "violation_score", nullable = false)
    private int violationScore;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "download_name", length = 255)
    private String downloadName;

    @Column(length = 20)
    private String extension;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256_original", length = 64)
    private String sha256Original;

    @Column(name = "sha256_stored", length = 64)
    private String sha256Stored;

    @Column(name = "claimed_mime", length = 100)
    private String claimedMime;

    @Column(name = "detected_type", length = 100)
    private String detectedType;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(length = 10)
    private String disposition;

    @Column(name = "storage_key", length = 100)
    private String storageKey;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected FileUploadRecord() {
    }

    public static FileUploadRecord rejected(UUID requestId, Long policyId, long policyVersion,
                                            String originalName, String extension, long sizeBytes,
                                            String claimedMime, String detectedType, String sha256Original,
                                            ReasonCode reason, String clientIp) {
        FileUploadRecord r = base(requestId, policyId, policyVersion, originalName, extension,
                sizeBytes, claimedMime, detectedType, clientIp);
        r.status = STATUS_REJECTED;
        r.rejectReason = reason.name();
        r.violationScore = reason.violationScore();
        r.sha256Original = sha256Original;
        return r;
    }

    public static FileUploadRecord stored(UUID requestId, Long policyId, long policyVersion,
                                          String originalName, String downloadName, String extension,
                                          long sizeBytes, String claimedMime, String detectedType,
                                          String sha256Original, String sha256Stored,
                                          String trustLevel, String contentType, String disposition,
                                          String storageKey, String clientIp) {
        FileUploadRecord r = base(requestId, policyId, policyVersion, originalName, extension,
                sizeBytes, claimedMime, detectedType, clientIp);
        r.status = STATUS_STORED;
        r.downloadName = downloadName;
        r.sha256Original = sha256Original;
        r.sha256Stored = sha256Stored;
        r.trustLevel = trustLevel;
        r.contentType = contentType;
        r.disposition = disposition;
        r.storageKey = storageKey;
        return r;
    }

    private static FileUploadRecord base(UUID requestId, Long policyId, long policyVersion,
                                         String originalName, String extension, long sizeBytes,
                                         String claimedMime, String detectedType, String clientIp) {
        FileUploadRecord r = new FileUploadRecord();
        r.publicId = UUID.randomUUID();
        r.requestId = requestId;
        r.policyId = policyId;
        r.policyVersion = policyVersion;
        // DB 길이(original_name 255, extension 20)를 넘는 값은 유효성 검사에서 이미 거부된 파일의 기록에만 올 수 있다.
        // 그 기록 저장이 실패해 요청 전체가 무너지지 않도록 여기서 한 번 더 맞춘다
        r.originalName = truncate(originalName, 255);
        r.extension = extension != null && extension.length() <= 20 ? extension : null;
        r.sizeBytes = sizeBytes;
        r.claimedMime = truncate(claimedMime, 100);
        r.detectedType = truncate(detectedType, 100);
        r.clientIp = clientIp;
        return r;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getStatus() {
        return status;
    }

    public String getDownloadName() {
        return downloadName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getDisposition() {
        return disposition;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalName() {
        return originalName;
    }
}
