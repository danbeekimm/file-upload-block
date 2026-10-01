package com.study.fileupload.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "upload_policy")
public class UploadPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "max_image_bytes", nullable = false)
    private long maxImageBytes;

    @Column(name = "max_file_bytes", nullable = false)
    private long maxFileBytes;

    @Column(name = "max_files_per_request", nullable = false)
    private int maxFilesPerRequest;

    @Column(name = "max_request_bytes", nullable = false)
    private long maxRequestBytes;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UploadPolicy() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public int getMaxFilesPerRequest() {
        return maxFilesPerRequest;
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }
}
