package com.study.fileupload.upload;

import com.study.fileupload.common.Actor;
import com.study.fileupload.common.ApiException;
import com.study.fileupload.common.ReasonCode;
import com.study.fileupload.config.UploadProperties;
import com.study.fileupload.guard.IpBlockService;
import com.study.fileupload.policy.PolicySnapshot;
import com.study.fileupload.policy.PolicyService;
import com.study.fileupload.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 판정 파이프라인 (명세 2-2).
 * 파일별로 독립 판정하는 부분 성공 방식이며, 요청 시작 시 읽은 정책 스냅샷을 요청 전체에 적용한다.
 * 거부된 파일도 사유·위반 점수·정책 버전과 함께 file_upload에 기록한다.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    /** 의심 신호 로그 (명세 11장) — 별도 마커 대신 WARN 레벨과 접두어로 구분 */
    private static final String SUSPICIOUS = "[SUSPICIOUS] ";

    private final PolicyService policyService;
    private final FileUploadRepository fileUploadRepository;
    private final ObjectStorage storage;
    private final IpBlockService ipBlockService;
    private final UploadProperties properties;

    public UploadService(PolicyService policyService,
                         FileUploadRepository fileUploadRepository,
                         ObjectStorage storage,
                         IpBlockService ipBlockService,
                         UploadProperties properties) {
        this.policyService = policyService;
        this.fileUploadRepository = fileUploadRepository;
        this.storage = storage;
        this.ipBlockService = ipBlockService;
        this.properties = properties;
    }

    public UploadResponse process(List<MultipartFile> files, Actor actor) {
        PolicySnapshot snapshot = policyService.snapshot();

        if (files == null || files.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_FILES", "업로드할 파일이 없습니다.");
        }
        // 파일 개수는 멀티파트 파싱 후에만 알 수 있다 (명세 2-1의 4번). 요청 단위 거부 → 로그만
        if (files.size() > snapshot.policy().getMaxFilesPerRequest()) {
            log.info("request rejected TOO_MANY_FILES ip={} count={}", actor.ip(), files.size());
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_FILES",
                    "한 번에 최대 " + snapshot.policy().getMaxFilesPerRequest() + "개까지 올릴 수 있습니다.");
        }

        UUID requestId = UUID.randomUUID();
        List<UploadResponse.FileResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(judgeAndRecord(file, snapshot, requestId, actor));
        }

        // 판정 기록이 쌓인 뒤 위반 점수 합산 → 임곗값 도달 시 일시 차단 생성 (명세 8장)
        ipBlockService.evaluate(actor.ip());

        return new UploadResponse(requestId, results);
    }

    private UploadResponse.FileResult judgeAndRecord(MultipartFile file, PolicySnapshot snap,
                                                     UUID requestId, Actor actor) {
        // 1. 파일명은 여기서 한 번만 추출·정제하고 이후 모든 단계가 이 값을 쓴다 (명세 3-1)
        String sanitized = FilenameProcessor.sanitize(file.getOriginalFilename());
        String claimedMime = file.getContentType();
        long size = file.getSize();
        FilenameInfo info = FilenameProcessor.parse(sanitized);
        String sha256Original = null;
        String detectedType = null;

        try {
            // 2. 파일명 유효성
            Optional<String> invalid = FilenameProcessor.validate(sanitized);
            if (invalid.isPresent()) {
                throw new RejectException(ReasonCode.INVALID_FILENAME, invalid.get());
            }

            // 3~4. 블랙리스트 (마지막 세그먼트 → 중간 세그먼트)
            if (info.hasExtension() && snap.isBlocked(info.extension())) {
                throw new RejectException(ReasonCode.BLOCKED_EXTENSION,
                        sanitized + " — " + info.extension() + " 파일은 보안 정책상 업로드할 수 없습니다.");
            }
            for (String middle : info.middleSegments()) {
                if (snap.isBlocked(middle)) {
                    throw new RejectException(ReasonCode.BLOCKED_INNER_EXTENSION,
                            sanitized + " — 파일명 중간의 ." + middle + "이(가) 차단 대상입니다. 파일명을 확인해 주세요.");
                }
            }

            // 5. 앞부분 읽기
            byte[] head = readHead(file);

            // 6. 실행 파일 시그니처 — 모든 파일에 적용되는 최소 방어선 (명세 5-1)
            Optional<String> executable = ExecutableSignatureDetector.detect(head, size);
            if (executable.isPresent()) {
                detectedType = executable.get();
                log.warn(SUSPICIOUS + "executable content ip={} name={} sig={}", actor.ip(), sanitized, executable.get());
                throw new RejectException(ReasonCode.EXECUTABLE_DETECTED,
                        sanitized + " — 실행 파일은 업로드할 수 없습니다.", executable.get());
            }

            // 7. 신뢰/비신뢰 분류 (명세 1장, 5-2)
            Optional<TrustedFileType> trustedOpt = info.hasExtension()
                    ? TrustedFileType.fromExtension(info.extension())
                    : Optional.empty();
            if (trustedOpt.isPresent() && !trustedOpt.get().matches(head)) {
                detectedType = "unknown";
                log.warn(SUSPICIOUS + "content mismatch ip={} name={} claimed={}", actor.ip(), sanitized, claimedMime);
                throw new RejectException(ReasonCode.CONTENT_MISMATCH,
                        sanitized + " — 파일 내용이 확장자와 일치하지 않습니다.");
            }
            if (info.hasExtension() && ZipInspector.UNSUPPORTED_ARCHIVE_EXTENSIONS.contains(info.extension())) {
                throw new RejectException(ReasonCode.ARCHIVE_UNSUPPORTED,
                        sanitized + " — zip 외의 압축 형식은 업로드할 수 없습니다.");
            }

            // 8. 탐지된 형식 기준 크기 검사 (확장자 기준이면 .txt를 붙여 우회 가능, 명세 6장)
            boolean isImage = trustedOpt.map(t -> t.category() == TrustedFileType.Category.IMAGE).orElse(false);
            long maxBytes = isImage ? snap.policy().getMaxImageBytes() : snap.policy().getMaxFileBytes();
            if (size > maxBytes) {
                String kind = isImage ? "이미지 파일은 " : "파일은 ";
                throw new RejectException(ReasonCode.SIZE_EXCEEDED,
                        sanitized + " — " + kind + (maxBytes / 1024 / 1024) + "MB를 넘을 수 없습니다. (현재 "
                                + String.format("%.1f", size / 1024.0 / 1024.0) + "MB)");
            }

            sha256Original = sha256(file);
            detectedType = trustedOpt.map(TrustedFileType::mime)
                    .orElse(isZipHead(head) ? "zip" : "unknown");
            if (claimedMime != null && trustedOpt.isPresent()
                    && !claimedMime.equalsIgnoreCase(trustedOpt.get().mime())) {
                log.warn(SUSPICIOUS + "claimed mime mismatch ip={} name={} claimed={} detected={}",
                        actor.ip(), sanitized, claimedMime, detectedType);
            }

            // 9~11. 형식별 내용 검사 → 제공 방식 결정 → 저장 + 기록
            return storeAndRecord(file, snap, requestId, actor, sanitized, info, head,
                    claimedMime, size, sha256Original, detectedType, trustedOpt.orElse(null));

        } catch (RejectException e) {
            recordRejection(requestId, snap, actor, sanitized, info, size, claimedMime,
                    detectedType, sha256Original, e);
            return UploadResponse.FileResult.rejected(sanitized, e.reason().name(), e.getMessage());
        } catch (IOException e) {
            log.error("upload processing failed name={}", sanitized, e);
            return UploadResponse.FileResult.rejected(sanitized, "INTERNAL_ERROR",
                    "파일 처리 중 오류가 발생했습니다. 다시 시도해 주세요.");
        }
    }

    private UploadResponse.FileResult storeAndRecord(MultipartFile file, PolicySnapshot snap,
                                                     UUID requestId, Actor actor, String sanitized,
                                                     FilenameInfo info, byte[] head, String claimedMime,
                                                     long size, String sha256Original, String detectedType,
                                                     TrustedFileType trusted) throws IOException {
        List<String> notices = new ArrayList<>();
        String downloadName = sanitized;
        String trustLevel;
        String contentType;
        String disposition;
        byte[] rerendered = null;

        if (trusted != null && trusted.category() == TrustedFileType.Category.IMAGE) {
            try (InputStream in = file.getInputStream()) {
                rerendered = ImageReRenderer.rerender(in, trusted, properties.image().maxPixels(), sanitized).bytes();
            }
            trustLevel = FileUploadRecord.TRUST_TRUSTED;
            contentType = trusted.storedMime();
            disposition = FileUploadRecord.DISPOSITION_INLINE;
            // 리렌더링으로 형식이 바뀐 경우(webp → PNG)에만 다운로드 확장자를 바꾸고 안내한다.
            // jpeg/jpg처럼 같은 형식의 표기 차이는 사용자가 올린 이름을 그대로 둔다
            if (!trusted.storedMime().equals(trusted.mime())) {
                String newExt = trusted.storeAs().extension();
                downloadName = replaceExtension(downloadName, newExt);
                notices.add(newExt.toUpperCase(Locale.ROOT) + "로 변환되어 저장되었습니다.");
            }
        } else if (trusted != null) {   // PDF (DOCUMENT)
            PdfInspector.Result scan;
            try (InputStream in = file.getInputStream()) {
                scan = PdfInspector.scan(in);
            }
            if (scan.reject()) {
                log.warn(SUSPICIOUS + "pdf active content ip={} name={} names={}", actor.ip(), sanitized, scan.foundNames());
                throw new RejectException(ReasonCode.ACTIVE_CONTENT,
                        sanitized + " — 문서에 외부 실행이나 첨부 파일 등 실행 가능한 요소가 포함되어 업로드할 수 없습니다.",
                        String.valueOf(scan.foundNames()));
            }
            trustLevel = FileUploadRecord.TRUST_TRUSTED;
            contentType = trusted.storedMime();
            if (scan.downgrade()) {
                disposition = FileUploadRecord.DISPOSITION_ATTACHMENT;
                notices.add("문서에 스크립트 등 확인이 필요한 요소가 있어 다운로드 방식으로만 제공됩니다.");
                log.info("pdf downgraded to attachment name={} names={}", sanitized, scan.foundNames());
            } else {
                disposition = FileUploadRecord.DISPOSITION_INLINE;
            }
            if (scan.openAction()) {
                log.warn(SUSPICIOUS + "pdf /OpenAction found ip={} name={}", actor.ip(), sanitized);
            }
        } else if ("zip".equals(info.extension())) {
            if (!isZipHead(head)) {
                throw new RejectException(ReasonCode.ARCHIVE_UNSUPPORTED,
                        sanitized + " — 압축 파일을 읽을 수 없습니다.");
            }
            try (InputStream in = file.getInputStream()) {
                ZipInspector.inspect(in, size, sanitized, snap::isBlocked, new ZipInspector.Config(
                        properties.archive().maxEntries(), properties.archive().maxRatio(),
                        properties.archive().maxTotalUncompressed().toBytes()));
            }
            trustLevel = FileUploadRecord.TRUST_UNTRUSTED;
            contentType = "application/octet-stream";
            disposition = FileUploadRecord.DISPOSITION_ATTACHMENT;
        } else {
            // 비신뢰: 그 외 전부. octet-stream + attachment + nosniff (명세 1장)
            trustLevel = FileUploadRecord.TRUST_UNTRUSTED;
            contentType = "application/octet-stream";
            disposition = FileUploadRecord.DISPOSITION_ATTACHMENT;
        }

        // 점 파일 이름 재부여 — 블랙리스트 검사가 끝난 뒤에 적용 (명세 3-6)
        if (downloadName.startsWith(".")) {
            downloadName = "_" + downloadName;
            notices.add("숨김 파일이 되지 않도록 다운로드 이름이 " + downloadName + "(으)로 변경됩니다.");
        }

        String storageKey = UUID.randomUUID().toString();
        String sha256Stored;
        if (rerendered != null) {
            storage.put(storageKey, rerendered, contentType);
            sha256Stored = sha256(rerendered);
        } else {
            try (InputStream in = file.getInputStream()) {
                storage.put(storageKey, in, size, contentType);
            }
            sha256Stored = sha256Original;   // 리렌더링하지 않은 파일은 원본과 같음
        }

        FileUploadRecord record = FileUploadRecord.stored(requestId, snap.policy().getId(),
                snap.policyVersion(), sanitized, downloadName, info.extension(), size,
                claimedMime, detectedType, sha256Original, sha256Stored,
                trustLevel, contentType, disposition, storageKey, actor.ip());
        try {
            fileUploadRepository.save(record);
        } catch (RuntimeException e) {
            // 저장 성공 후 DB 기록 실패 → 저장한 객체 삭제 (명세 2-2)
            try {
                storage.delete(storageKey);
            } catch (IOException deleteError) {
                log.error("orphan object cleanup failed key={}", storageKey, deleteError);
            }
            throw e;
        }
        log.info("file stored name={} key={} trust={} disposition={}", sanitized, storageKey, trustLevel, disposition);
        return UploadResponse.FileResult.stored(sanitized, record.getPublicId(), disposition, notices);
    }

    private void recordRejection(UUID requestId, PolicySnapshot snap, Actor actor, String sanitized,
                                 FilenameInfo info, long size, String claimedMime, String detectedType,
                                 String sha256Original, RejectException e) {
        // 차단은 정상 동작 — ERROR가 아닌 INFO/WARN (명세 11장)
        log.info("file rejected reason={} name={} ip={} detail={}",
                e.reason(), sanitized, actor.ip(), e.logDetail());
        fileUploadRepository.save(FileUploadRecord.rejected(requestId, snap.policy().getId(),
                snap.policyVersion(), sanitized.isEmpty() ? "(이름 없음)" : sanitized,
                info.extension(), size, claimedMime, detectedType, sha256Original,
                e.reason(), actor.ip()));
    }

    private byte[] readHead(MultipartFile file) throws IOException {
        byte[] buffer = new byte[properties.inspect().headBytes()];
        try (InputStream in = file.getInputStream()) {
            int total = 0;
            int n;
            while (total < buffer.length && (n = in.read(buffer, total, buffer.length - total)) != -1) {
                total += n;
            }
            return total == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, total);
        }
    }

    private static boolean isZipHead(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K'
                && (head[2] == 3 || head[2] == 5 || head[2] == 7)
                && (head[3] == 4 || head[3] == 6 || head[3] == 8);
    }

    private static String replaceExtension(String name, String newExt) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "." + newExt;
    }

    private String sha256(MultipartFile file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = file.getInputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] data) {
        return HexFormat.of().formatHex(newSha256().digest(data));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
