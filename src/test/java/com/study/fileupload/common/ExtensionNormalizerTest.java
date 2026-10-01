package com.study.fileupload.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 커스텀 확장자 입력 정규화 (명세 4-1) */
class ExtensionNormalizerTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
    @DisplayName("자동 보정: 점, 대문자, 전각, 공백")
    @CsvSource(delimiter = '|', value = {
            ".sh    | sh",
            "SH     | sh",
            "ｓｈ    | sh",
            "'  sh '| sh",
            "..exe  | exe",
            "PHP7   | php7",
    })
    void normalize_corrects(String raw, String expected) {
        assertThat(ExtensionNormalizer.normalizeCustomInput(raw.trim())).contains(expected);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" 거부")
    @DisplayName("의도가 모호한 입력은 거부")
    @CsvSource(delimiter = '|', value = {
            "s h",
            "sh!",
            "tar.gz",
            "한글",
            "''",
            ".",
            "aaaaaaaaaaaaaaaaaaaaa",   // 21자
    })
    void normalize_rejects(String raw) {
        assertThat(ExtensionNormalizer.normalizeCustomInput(raw)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] 세그먼트 \"{0}\" → \"{1}\"")
    @DisplayName("비교용 세그먼트 정규화 (3-4): 전각·제로 폭 문자·대소문자")
    @CsvSource(delimiter = '|', value = {
            "EXE   | exe",
            "ｅｘｅ | exe",
            "ex​e | exe",
            "' Js '| js",
    })
    void normalizeSegment(String raw, String expected) {
        assertThat(ExtensionNormalizer.normalizeSegment(raw.trim())).isEqualTo(expected);
    }
}
