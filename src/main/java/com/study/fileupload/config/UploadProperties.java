package com.study.fileupload.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** 명세 14장의 설정값. 크기·개수 제한은 DB upload_policy에 있다 (6장). */
@ConfigurationProperties(prefix = "upload")
public record UploadProperties(
        RateLimit rateLimit,
        IpBlock ipBlock,
        Image image,
        Archive archive,
        Inspect inspect
) {

    public record RateLimit(Duration window, int maxRequests) {
    }

    public record IpBlock(Duration window, int threshold, Duration duration) {
    }

    public record Image(long maxPixels) {
    }

    public record Archive(int maxEntries, int maxRatio, DataSize maxTotalUncompressed) {
    }

    public record Inspect(int headBytes) {
    }
}
