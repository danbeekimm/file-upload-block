package com.study.fileupload.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** 파일명 공격 케이스 (명세 3장, 18장) */
class FilenameProcessorTest {

    /* ---------- 3-1 저장용 정제 ---------- */

    @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
    @DisplayName("저장용 정제: 경로 제거, 끝의 점·공백 제거")
    @CsvSource(delimiter = '|', value = {
            "C:\\Users\\evil\\..\\file.txt | file.txt",
            "../../evil.jsp               | evil.jsp",
            "dir/sub/name.png             | name.png",
            "file.exe.                    | file.exe",
            "'file.exe '                  | file.exe",
            "'file.exe. . '               | file.exe",
    })
    void sanitize_basic(String raw, String expected) {
        assertThat(FilenameProcessor.sanitize(raw.trim())).isEqualTo(expected);
    }

    @Test
    @DisplayName("RTLO(U+202E) 등 서식 문자와 제어 문자를 제거한다")
    void sanitize_removesFormatAndControlChars() {
        assertThat(FilenameProcessor.sanitize("\u202Egpj.exe")).isEqualTo("gpj.exe");
        assertThat(FilenameProcessor.sanitize("line\nbreak.txt")).isEqualTo("linebreak.txt");
        assertThat(FilenameProcessor.sanitize("nul\u0000l.txt")).isEqualTo("null.txt");
        assertThat(FilenameProcessor.sanitize("ex\u200Be.zip")).isEqualTo("exe.zip");   // 제로 폭 문자
    }

    @Test
    @DisplayName("NFC 정규화: macOS 자모 분리(NFD) 한글을 합친다")
    void sanitize_nfc() {
        String nfd = "\u1112\u1161\u11AB.txt";   // ㅎ+ㅏ+ㄴ (분리)
        assertThat(FilenameProcessor.sanitize(nfd)).isEqualTo("한.txt");
    }

    /* ---------- 3-2 유효성 ---------- */

    @Test
    @DisplayName("빈 이름, 점만 있는 이름, 255바이트 초과는 거부한다")
    void validate_rejects() {
        assertThat(FilenameProcessor.validate("")).isPresent();
        assertThat(FilenameProcessor.validate(FilenameProcessor.sanitize("..."))).isPresent();
        // 한글 86자 = 258바이트 > 255 (명세 18장)
        assertThat(FilenameProcessor.validate("가".repeat(86))).isPresent();
        assertThat(FilenameProcessor.validate("가".repeat(85))).isEmpty();   // 255바이트 = 통과
        assertThat(FilenameProcessor.validate("a".repeat(255))).isEmpty();
        assertThat(FilenameProcessor.validate("a".repeat(256))).isPresent();
    }

    @Test
    @DisplayName("확장자 20자 초과와 254바이트 초과 점 파일은 거부한다 — DB 길이(extension 20, download_name 255)와 일치")
    void validate_lengthLimitsMatchDb() {
        assertThat(FilenameProcessor.validate("a." + "b".repeat(20))).isEmpty();
        assertThat(FilenameProcessor.validate("a." + "b".repeat(21))).isPresent();
        assertThat(FilenameProcessor.validate("." + "a".repeat(249) + ".txt")).isEmpty();    // 254바이트 → _ 접두어 후 255
        assertThat(FilenameProcessor.validate("." + "a".repeat(250) + ".txt")).isPresent();  // 255바이트 → _ 접두어 후 256
    }

    /* ---------- 3-3 세그먼트 ---------- */

    static Stream<Arguments> segmentCases() {
        return Stream.of(
                // 파일명, 기대 확장자(null=없음), 기대 중간 세그먼트들
                Arguments.of("report.final.pdf", "pdf", new String[]{"final"}),
                Arguments.of("setup.EXE", "exe", new String[]{}),
                Arguments.of("file.exe.txt", "txt", new String[]{"exe"}),
                Arguments.of("com.example.config.txt", "txt", new String[]{"example", "config"}),   // 첫 세그먼트 com 제외
                Arguments.of(".exe", "exe", new String[]{}),          // 점 파일은 확장자 후보 포함
                Arguments.of(".env", "env", new String[]{}),
                Arguments.of("README", null, new String[]{}),         // 점 없음 → 확장자 없음
                Arguments.of("backdoor", null, new String[]{}),
                Arguments.of("a.tar.gz", "gz", new String[]{"tar"}),
                Arguments.of("file.ｅｘｅ", "exe", new String[]{}),     // 전각 → NFKC 비교 정규화
                Arguments.of("my.js.notes.txt", "txt", new String[]{"js", "notes"})
        );
    }

    @ParameterizedTest(name = "[{index}] {0} → ext={1}")
    @MethodSource("segmentCases")
    @DisplayName("세그먼트 분해: 첫 세그먼트 제외, 마지막이 확장자, 비교용 정규화")
    void parse_segments(String name, String expectedExt, String[] expectedMiddles) {
        FilenameInfo info = FilenameProcessor.parse(FilenameProcessor.sanitize(name));
        assertThat(info.extension()).isEqualTo(expectedExt);
        assertThat(info.middleSegments()).containsExactly(expectedMiddles);
    }

    @Test
    @DisplayName("정제와 분해를 연결: RTLO 위장 gpj.exe는 exe로 판정된다 (명세 18장)")
    void rtlo_disguise_endsUpAsExe() {
        FilenameInfo info = FilenameProcessor.parse(FilenameProcessor.sanitize("\u202Egpj.exe"));
        assertThat(info.extension()).isEqualTo("exe");
    }

    @Test
    @DisplayName("끝의 점 우회: file.exe.은 exe로 판정된다 (명세 18장)")
    void trailingDot_endsUpAsExe() {
        FilenameInfo info = FilenameProcessor.parse(FilenameProcessor.sanitize("file.exe."));
        assertThat(info.extension()).isEqualTo("exe");
    }
}
