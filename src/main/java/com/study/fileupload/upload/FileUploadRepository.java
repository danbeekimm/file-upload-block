package com.study.fileupload.upload;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileUploadRepository extends JpaRepository<FileUploadRecord, Long> {

    Optional<FileUploadRecord> findByPublicId(UUID publicId);

    /** 요청 수 제한: 최근 창 안의 업로드 요청 수 (요청 1회 = request_id 1개, 명세 6장) */
    @Query("select count(distinct f.requestId) from FileUploadRecord f "
            + "where f.clientIp = :ip and f.createdAt >= :since")
    long countRecentRequests(@Param("ip") String ip, @Param("since") Instant since);

    /** IP 차단 판정: 최근 창 안의 위반 점수 합 (명세 8-2) */
    @Query("select coalesce(sum(f.violationScore), 0) from FileUploadRecord f "
            + "where f.clientIp = :ip and f.createdAt >= :since")
    long sumRecentViolationScore(@Param("ip") String ip, @Param("since") Instant since);
}
