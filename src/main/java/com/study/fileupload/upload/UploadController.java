package com.study.fileupload.upload;

import com.study.fileupload.common.ActorResolver;
import com.study.fileupload.common.ApiException;
import com.study.fileupload.storage.ObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadService uploadService;
    private final FileUploadRepository fileUploadRepository;
    private final ObjectStorage storage;
    private final ActorResolver actorResolver;

    public UploadController(UploadService uploadService,
                            FileUploadRepository fileUploadRepository,
                            ObjectStorage storage,
                            ActorResolver actorResolver) {
        this.uploadService = uploadService;
        this.fileUploadRepository = fileUploadRepository;
        this.storage = storage;
        this.actorResolver = actorResolver;
    }

    @PostMapping
    public UploadResponse upload(@RequestParam("files") List<MultipartFile> files,
                                 HttpServletRequest request) {
        return uploadService.process(files, actorResolver.resolve(request));
    }

    /**
     * 다운로드 (명세 7장). public_id로만 접근 — 순차 id 미노출(IDOR 방지).
     * Content-Type/Disposition은 판정 시 저장한 값을 그대로 사용하고, nosniff를 항상 붙인다.
     */
    @GetMapping("/{publicId}")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID publicId) throws IOException {
        FileUploadRecord record = fileUploadRepository.findByPublicId(publicId)
                .filter(r -> FileUploadRecord.STATUS_STORED.equals(r.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "파일을 찾을 수 없습니다."));

        InputStream stream = storage.get(record.getStorageKey());
        boolean inline = FileUploadRecord.DISPOSITION_INLINE.equals(record.getDisposition());
        String downloadName = record.getDownloadName() != null ? record.getDownloadName() : "download";

        // filename*(RFC 5987)과 ASCII fallback을 함께 생성 — 한글 파일명 대응
        ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(downloadName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(record.getContentType()))
                .body(new InputStreamResource(stream));
    }
}
