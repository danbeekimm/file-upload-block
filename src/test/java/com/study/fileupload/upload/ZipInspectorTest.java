package com.study.fileupload.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.study.fileupload.common.ReasonCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** zip 내부 항목 검사 (명세 5-5) */
class ZipInspectorTest {

    private static final Set<String> BLOCKED = Set.of("exe", "sh", "js");
    private static final ZipInspector.Config CONFIG =
            new ZipInspector.Config(100, 100, 200L * 1024 * 1024);

    private static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static void inspect(byte[] zip) {
        ZipInspector.inspect(new ByteArrayInputStream(zip), zip.length, "test.zip",
                BLOCKED::contains, CONFIG);
    }

    @Test
    @DisplayName("정상 zip은 통과한다")
    void cleanZipPasses() throws IOException {
        byte[] zip = zipOf(Map.of("docs/readme.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        assertThatCode(() -> inspect(zip)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("내부 항목이 차단 확장자면 거부 (zip 안의 a.exe, 명세 18장)")
    void blockedEntryExtension() throws IOException {
        byte[] zip = zipOf(Map.of("a.exe", "not even a real exe".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> inspect(zip))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.BLOCKED_EXTENSION));
    }

    @Test
    @DisplayName("내부 항목의 중간 세그먼트 차단 (a.js.txt)")
    void blockedInnerSegment() throws IOException {
        byte[] zip = zipOf(Map.of("a.js.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> inspect(zip))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.BLOCKED_INNER_EXTENSION));
    }

    @Test
    @DisplayName("내부 항목이 실행 파일 시그니처면 이름과 무관하게 거부 (내부 ELF, 명세 18장)")
    void executableEntryContent() throws IOException {
        byte[] elf = new byte[128];
        elf[0] = 0x7F;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        byte[] zip = zipOf(Map.of("innocent.txt", elf));
        assertThatThrownBy(() -> inspect(zip))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.EXECUTABLE_DETECTED));
    }

    @Test
    @DisplayName("중첩 압축 파일은 거부 (zip 안의 zip)")
    void nestedZip() throws IOException {
        byte[] inner = zipOf(Map.of("x.txt", "x".getBytes(StandardCharsets.UTF_8)));
        byte[] outer = zipOf(Map.of("backup.zip", inner));
        assertThatThrownBy(() -> inspect(outer))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.ARCHIVE_NESTED));
    }

    @Test
    @DisplayName("확장자를 바꾼 중첩 압축도 시그니처로 잡는다 (data.bin이 zip)")
    void nestedZipDisguisedBySignature() throws IOException {
        byte[] inner = zipOf(Map.of("x.txt", "x".getBytes(StandardCharsets.UTF_8)));
        byte[] outer = zipOf(Map.of("data.bin", inner));
        assertThatThrownBy(() -> inspect(outer))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.ARCHIVE_NESTED));
    }

    @Test
    @DisplayName("docx 등 문서 확장자는 zip 시그니처여도 중첩으로 보지 않는다 (명세 5-5)")
    void docxIsNotNested() throws IOException {
        byte[] innerDocx = zipOf(Map.of("word/document.xml", "<w/>".getBytes(StandardCharsets.UTF_8)));
        byte[] outer = zipOf(Map.of("report.docx", innerDocx));
        assertThatCode(() -> inspect(outer)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("항목 수 상한 (100개 이상 거부, 명세 18장의 150개 케이스)")
    void tooManyEntries() throws IOException {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 150; i++) {
            entries.put("file" + i + ".txt", new byte[]{1});
        }
        byte[] zip = zipOf(entries);
        assertThatThrownBy(() -> inspect(zip))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.ARCHIVE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("압축률 100:1 이상은 압축 폭탄으로 거부")
    void zipBombByRatio() throws IOException {
        byte[] zeros = new byte[10 * 1024 * 1024];   // 0으로 채운 10MB → 압축 후 약 10KB
        byte[] zip = zipOf(Map.of("zeros.bin", zeros));
        assertThat(zip.length).isLessThan(zeros.length / 100);   // 전제 확인
        assertThatThrownBy(() -> inspect(zip))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.ARCHIVE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("암호화된 zip은 거부 (일반 목적 플래그 bit 0)")
    void encryptedZip() {
        byte[] zip = encryptedZipBytes();
        assertThatThrownBy(() -> inspect(zip))
                .isInstanceOfSatisfying(RejectException.class,
                        e -> assertThat(e.reason()).isEqualTo(ReasonCode.ARCHIVE_ENCRYPTED));
    }

    /** java.util.zip은 암호화 zip을 만들 수 없어, 암호화 플래그가 켜진 로컬 파일 헤더를 직접 구성 */
    private static byte[] encryptedZipBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] name = "a.txt".getBytes(StandardCharsets.US_ASCII);
        byte[] data = {1, 2, 3, 4};
        writeLe(out, 0x04034b50, 4);   // local file header signature
        writeLe(out, 20, 2);           // version needed
        writeLe(out, 0x0001, 2);       // general purpose flags: bit 0 = encrypted
        writeLe(out, 0, 2);            // method: stored
        writeLe(out, 0, 2);            // time
        writeLe(out, 0, 2);            // date
        writeLe(out, 0, 4);            // crc
        writeLe(out, data.length, 4);  // compressed size
        writeLe(out, data.length, 4);  // uncompressed size
        writeLe(out, name.length, 2);  // name length
        writeLe(out, 0, 2);            // extra length
        out.writeBytes(name);
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static void writeLe(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((value >>> (8 * i)) & 0xFF);
        }
    }
}
