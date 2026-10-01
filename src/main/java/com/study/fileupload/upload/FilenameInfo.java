package com.study.fileupload.upload;

import java.util.List;

/**
 * 정제·분해된 파일명 (명세 3장).
 *
 * @param sanitizedName  저장용 정제를 거친 원본 파일명 (original_name에 저장)
 * @param extension      마지막 세그먼트의 비교용 정규화 값. 확장자 없으면 null
 * @param middleSegments 중간 세그먼트들의 비교용 정규화 값 (첫 세그먼트 제외)
 * @param dotFile        점으로 시작하는 파일명 여부 (다운로드 이름 재부여 대상, 3-6)
 */
public record FilenameInfo(
        String sanitizedName,
        String extension,
        List<String> middleSegments,
        boolean dotFile
) {

    public boolean hasExtension() {
        return extension != null && !extension.isEmpty();
    }
}
