package com.study.fileupload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage")
public record StorageProperties(String mode, Local local, S3 s3) {

    public record Local(String dir) {
    }

    /**
     * accessKeyId / secretAccessKey 는 환경 변수 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 에서 바인딩된다
     * (.env.example 참고). 둘 다 비어 있으면 SDK 기본 자격 증명 체인(EC2 인스턴스 역할 등)을 쓴다.
     */
    public record S3(String bucket, String region, String prefix, String accessKeyId, String secretAccessKey) {
    }
}
