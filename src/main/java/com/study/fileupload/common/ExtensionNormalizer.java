package com.study.fileupload.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 확장자 정규화 (명세 3-4, 4-1).
 * 업로드 판정의 세그먼트 비교와 커스텀 확장자 입력이 같은 구현을 공유한다.
 */
public final class ExtensionNormalizer {

    /** 저장 가능한 확장자 형식. DB CHECK(ck_extension_rule_format)와 동일해야 한다. */
    public static final Pattern VALID_EXTENSION = Pattern.compile("^[a-z0-9]{1,20}$");

    private ExtensionNormalizer() {
    }

    /**
     * 비교용 정규화 (3-4): NFKC → 서식 문자(Cf) 제거 → 앞뒤 공백 제거 → 소문자(Locale.ROOT).
     * 전각(ｅｘｅ)과 제로 폭 문자를 이용한 우회를 무력화한다. 저장 이름은 바꾸지 않는다.
     */
    public static String normalizeSegment(String segment) {
        String s = Normalizer.normalize(segment, Normalizer.Form.NFKC);
        s = removeFormatChars(s);
        s = s.strip();
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * 커스텀 확장자 입력 정규화 (4-1): 비교용 정규화 + 앞쪽 점 제거 + 형식 검사.
     * 20자 제한은 정규화 이후 값에 적용한다 (NFKC는 길이를 바꿀 수 있음).
     *
     * @return 정규화된 확장자. 형식에 맞지 않으면 empty (의도가 모호하므로 거부).
     */
    public static Optional<String> normalizeCustomInput(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = removeFormatChars(s);
        s = s.strip();
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        s = s.toLowerCase(Locale.ROOT);
        return VALID_EXTENSION.matcher(s).matches() ? Optional.of(s) : Optional.empty();
    }

    private static String removeFormatChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints()
                .filter(cp -> Character.getType(cp) != Character.FORMAT)
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }
}
