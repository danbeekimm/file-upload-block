package com.study.fileupload.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 로컬 파일시스템 저장소 (개발·테스트용).
 * 웹 루트와 무관한 디렉터리에 UUID 키로만 저장하므로 URL로 직접 접근·실행될 수 없다.
 */
public class LocalObjectStorage implements ObjectStorage {

    private final Path baseDir;

    public LocalObjectStorage(Path baseDir) throws IOException {
        this.baseDir = baseDir;
        Files.createDirectories(baseDir);
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        Files.write(resolve(key), data);
    }

    @Override
    public void put(String key, InputStream data, long length, String contentType) throws IOException {
        Files.copy(data, resolve(key), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream get(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /** 키는 서버가 만든 UUID뿐이지만, 방어적으로 경로 이탈을 차단한다. */
    private Path resolve(String key) {
        Path path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir)) {
            throw new IllegalArgumentException("잘못된 저장 키: " + key);
        }
        return path;
    }
}
