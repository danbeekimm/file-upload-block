package com.study.fileupload.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 객체 저장소. 키는 UUID(확장자 없음), 원본 파일명은 DB에만 둔다 (명세 7장).
 * 업로드는 반드시 서버 검증을 거친 뒤에만 호출된다 — 클라이언트 직접 업로드 없음.
 */
public interface ObjectStorage {

    void put(String key, byte[] data, String contentType) throws IOException;

    void put(String key, InputStream data, long length, String contentType) throws IOException;

    InputStream get(String key) throws IOException;

    void delete(String key) throws IOException;
}
