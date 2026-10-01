package com.study.fileupload.storage;

import com.study.fileupload.config.StorageProperties;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class StorageConfig {

    @Bean
    public ObjectStorage objectStorage(StorageProperties props) throws IOException {
        if ("s3".equalsIgnoreCase(props.mode())) {
            StorageProperties.S3 s3Props = props.s3();
            S3Client s3 = S3Client.builder()
                    .region(Region.of(s3Props.region()))
                    .credentialsProvider(credentialsProvider(s3Props))
                    .build();
            return new S3ObjectStorage(s3, s3Props.bucket(), s3Props.prefix());
        }
        return new LocalObjectStorage(Path.of(props.local().dir()).toAbsolutePath().normalize());
    }

    /**
     * 자격 증명은 .env(.env.example)의 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 를 그대로 사용한다.
     * 둘 다 비어 있으면 SDK 기본 체인(EC2 인스턴스 역할 등)으로 폴백하고, 한쪽만 있으면 설정 실수이므로 기동을 막는다.
     */
    static AwsCredentialsProvider credentialsProvider(StorageProperties.S3 s3) {
        boolean hasAccessKey = hasText(s3.accessKeyId());
        boolean hasSecretKey = hasText(s3.secretAccessKey());
        if (hasAccessKey && hasSecretKey) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(s3.accessKeyId().trim(), s3.secretAccessKey().trim()));
        }
        if (hasAccessKey || hasSecretKey) {
            throw new IllegalStateException(
                    "AWS_ACCESS_KEY_ID 와 AWS_SECRET_ACCESS_KEY 는 함께 설정해야 합니다 (.env.example 참고)");
        }
        return DefaultCredentialsProvider.create();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
