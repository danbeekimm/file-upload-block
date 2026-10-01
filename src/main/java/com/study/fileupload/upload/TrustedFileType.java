package com.study.fileupload.upload;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 신뢰 목록 (명세 5-2). DB가 아니라 enum으로 관리 —
 * 확장자·MIME·시그니처·리렌더링 방식을 한곳에 모아 목록과 검증 로직이 어긋날 수 없게 하고,
 * 신뢰 부여(inline 허용)라는 강한 결정을 코드 리뷰·테스트·배포로 통제한다.
 *
 * Office 문서·hwp·SVG는 의도적으로 제외 (검사 비용·파서 위험·스크립트 포함 가능성).
 */
public enum TrustedFileType {

    JPG("jpg", Category.IMAGE, "image/jpeg", true, StoreAs.JPEG, sig(0, "FFD8FF")),
    JPEG("jpeg", Category.IMAGE, "image/jpeg", true, StoreAs.JPEG, sig(0, "FFD8FF")),
    PNG("png", Category.IMAGE, "image/png", true, StoreAs.PNG, sig(0, "89504E470D0A1A0A")),
    GIF("gif", Category.IMAGE, "image/gif", true, StoreAs.GIF, sig(0, "47494638")),
    WEBP("webp", Category.IMAGE, "image/webp", true, StoreAs.PNG, sig(0, "52494646"), sig(8, "57454250")),
    PDF("pdf", Category.DOCUMENT, "application/pdf", true, StoreAs.ORIGINAL, sig(0, "255044462D"));

    public enum Category { IMAGE, DOCUMENT }

    /** 리렌더링 결과 형식. webp는 PNG로 재저장한다 (5-3). */
    public enum StoreAs {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        GIF("image/gif", "gif"),
        ORIGINAL(null, null);

        private final String mime;
        private final String extension;

        StoreAs(String mime, String extension) {
            this.mime = mime;
            this.extension = extension;
        }

        public String mime() {
            return mime;
        }

        public String extension() {
            return extension;
        }
    }

    /** (오프셋, 바이트) 시그니처. 모두 일치해야 해당 형식 (webp처럼 두 위치를 보는 형식 대응). */
    public record Signature(int offset, byte[] bytes) {
        public boolean matches(byte[] head) {
            if (head.length < offset + bytes.length) {
                return false;
            }
            return Arrays.equals(head, offset, offset + bytes.length, bytes, 0, bytes.length);
        }
    }

    private static final Map<String, TrustedFileType> BY_EXTENSION =
            Arrays.stream(values()).collect(Collectors.toMap(t -> t.extension, Function.identity()));

    private final String extension;
    private final Category category;
    private final String mime;
    private final boolean inlineAllowed;   // 형식 차원의 최대치. 파일별 최종 판정은 disposition에 저장
    private final StoreAs storeAs;
    private final List<Signature> signatures;

    TrustedFileType(String extension, Category category, String mime,
                    boolean inlineAllowed, StoreAs storeAs, Signature... signatures) {
        this.extension = extension;
        this.category = category;
        this.mime = mime;
        this.inlineAllowed = inlineAllowed;
        this.storeAs = storeAs;
        this.signatures = List.of(signatures);
    }

    public static Optional<TrustedFileType> fromExtension(String normalizedExtension) {
        return Optional.ofNullable(BY_EXTENSION.get(normalizedExtension));
    }

    public boolean matches(byte[] head) {
        return signatures.stream().allMatch(s -> s.matches(head));
    }

    public String extension() {
        return extension;
    }

    public Category category() {
        return category;
    }

    public String mime() {
        return mime;
    }

    public boolean inlineAllowed() {
        return inlineAllowed;
    }

    public StoreAs storeAs() {
        return storeAs;
    }

    /** 저장/제공 MIME: 리렌더링으로 형식이 바뀌면 바뀐 값 (webp → image/png) */
    public String storedMime() {
        return storeAs.mime() != null ? storeAs.mime() : mime;
    }

    private static Signature sig(int offset, String hex) {
        return new Signature(offset, HexFormat.of().parseHex(hex));
    }
}
