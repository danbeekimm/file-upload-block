package com.study.fileupload.storage;

import java.io.IOException;
import java.io.InputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 저장소. 버킷은 비공개이며 다운로드는 반드시 애플리케이션을 거친다 —
 * Content-Type/Content-Disposition/nosniff 헤더를 서버가 통제하기 위함 (명세 7장).
 * 자격 증명은 환경 변수 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY(.env)로 받고 코드·저장소에 두지 않는다 (StorageConfig).
 */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3ObjectStorage(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(prefix + key).contentType(contentType).build(),
                    RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            throw new IOException("S3 업로드 실패: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    @Override
    public void put(String key, InputStream data, long length, String contentType) throws IOException {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(prefix + key).contentType(contentType).build(),
                    RequestBody.fromInputStream(data, length));
        } catch (S3Exception e) {
            throw new IOException("S3 업로드 실패: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) throws IOException {
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build());
        } catch (S3Exception e) {
            throw new IOException("S3 조회 실패: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(prefix + key).build());
        } catch (S3Exception e) {
            throw new IOException("S3 삭제 실패: " + e.awsErrorDetails().errorMessage(), e);
        }
    }
}
