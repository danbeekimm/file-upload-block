package com.study.fileupload.upload;

import com.study.fileupload.common.ExtensionNormalizer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 파일명 저장용 정제(3-1), 유효성(3-2), 세그먼트 분해(3-3).
 * 모든 단계(업로드 판정, zip 내부 항목)가 같은 구현을 사용한다.
 */
public final class FilenameProcessor {

    public static final int MAX_NAME_UTF8_BYTES = 255;
    /** DB file_upload.extension VARCHAR(20)과 동일 */
    public static final int MAX_EXTENSION_LENGTH = 20;

    private FilenameProcessor() {
    }

    /**
     * 저장용 정제 (3-1): 경로 제거 → NFC → 제어(Cc)·서식(Cf) 문자 제거 → 끝의 점·공백 제거.
     * RTLO(U+202E) 같은 표시 위장과 개행을 이용한 로그 위조를 함께 막는다.
     */
    public static String sanitize(String rawName) {
        if (rawName == null) {
            return "";
        }
        String name = rawName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = Normalizer.normalize(name, Normalizer.Form.NFC);

        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints()
                .filter(cp -> {
                    int type = Character.getType(cp);
                    return type != Character.CONTROL && type != Character.FORMAT;
                })
                .forEach(sb::appendCodePoint);
        name = sb.toString();

        // 끝의 점·공백: Windows가 저장 시 제거하는 것을 악용한 우회 (file.exe. → file.exe)
        int end = name.length();
        while (end > 0) {
            char c = name.charAt(end - 1);
            if (c == '.' || c == ' ') {
                end--;
            } else {
                break;
            }
        }
        return name.substring(0, end);
    }

    /**
     * 유효성 검사 (3-2). 실패 사유를 담은 메시지를 반환하고, 통과하면 empty.
     * 길이는 잘라내지 않고 거부한다 — 잘라내다 확장자가 잘리면 검증과 저장이 어긋난다.
     * 점으로 시작하는 이름은 다운로드 이름에 '_'가 붙으므로(3-6) 1바이트 여유를 두고,
     * 확장자는 DB 컬럼 길이(20)를 넘으면 거부한다 — 둘 다 넘기면 기록 저장이 실패해 요청 전체가 500이 된다.
     */
    public static Optional<String> validate(String sanitizedName) {
        if (sanitizedName.isEmpty()) {
            return Optional.of("파일 이름이 비어 있습니다.");
        }
        if (sanitizedName.chars().allMatch(c -> c == '.')) {
            return Optional.of("파일 이름이 올바르지 않습니다.");
        }
        int maxBytes = sanitizedName.startsWith(".") ? MAX_NAME_UTF8_BYTES - 1 : MAX_NAME_UTF8_BYTES;
        if (sanitizedName.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return Optional.of("파일 이름이 너무 깁니다. (최대 255바이트)");
        }
        FilenameInfo info = parse(sanitizedName);
        if (info.hasExtension() && info.extension().length() > MAX_EXTENSION_LENGTH) {
            return Optional.of("확장자가 너무 깁니다. (최대 20자)");
        }
        return Optional.empty();
    }

    /**
     * 세그먼트 분해 (3-3) + 비교용 정규화 (3-4).
     * 첫 세그먼트(기본 이름)는 항상 제외 — com.example.config.txt의 "com"이 막히지 않게.
     * 점으로 시작하는 파일(.exe)은 빈 첫 세그먼트가 제외되므로 "exe"가 확장자가 된다.
     */
    public static FilenameInfo parse(String sanitizedName) {
        boolean dotFile = sanitizedName.startsWith(".");
        if (!sanitizedName.contains(".")) {
            return new FilenameInfo(sanitizedName, null, List.of(), dotFile);
        }
        String[] segments = sanitizedName.split("\\.", -1);
        // segments.length >= 2 (점이 있으므로). 마지막이 확장자, index 1..n-2가 중간
        String extension = ExtensionNormalizer.normalizeSegment(segments[segments.length - 1]);
        if (extension.isEmpty()) {
            extension = null;   // "a." 형태는 sanitize에서 제거되지만 방어적으로 처리
        }
        List<String> middles = new ArrayList<>();
        for (int i = 1; i < segments.length - 1; i++) {
            String normalized = ExtensionNormalizer.normalizeSegment(segments[i]);
            if (!normalized.isEmpty()) {
                middles.add(normalized);
            }
        }
        return new FilenameInfo(sanitizedName, extension, List.copyOf(middles), dotFile);
    }
}
