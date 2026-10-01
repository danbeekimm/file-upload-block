package com.study.fileupload.upload;

import com.study.fileupload.common.ReasonCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

/**
 * zip 내부 항목 검사 (명세 5-5).
 * - 판별 기준은 확장자 (시그니처 PK로 하면 docx/jar/apk까지 압축 정책을 받음)
 * - 디스크에 풀지 않고 스트림으로만 읽는다 → zip slip이 성립하지 않음
 * - 크기는 헤더 값이 아니라 실제로 읽은 바이트 수로 센다 (헤더는 조작 가능)
 */
public final class ZipInspector {

    /** zip 외 압축 형식 — 내부를 검사할 수 없으므로 거부 (ARCHIVE_UNSUPPORTED) */
    public static final Set<String> UNSUPPORTED_ARCHIVE_EXTENSIONS =
            Set.of("7z", "rar", "tar", "gz", "tgz", "bz2", "xz");

    /** 중첩 판정에서 문서로 보고 제외하는 확장자 (재귀 검사도 하지 않음 — 한계는 명세 5-5) */
    private static final Set<String> DOCUMENT_EXCEPTIONS = Set.of("docx", "xlsx", "pptx", "odt", "ods", "odp");

    private static final Set<String> NESTED_ARCHIVE_EXTENSIONS;

    static {
        var set = new java.util.HashSet<>(UNSUPPORTED_ARCHIVE_EXTENSIONS);
        set.add("zip");
        NESTED_ARCHIVE_EXTENSIONS = Set.copyOf(set);
    }

    private static final int HEAD_BYTES = 8192;

    private ZipInspector() {
    }

    public record Config(int maxEntries, int maxRatio, long maxTotalUncompressedBytes) {
    }

    /**
     * @param blockedExtension 정규화된 확장자가 차단 대상인지 판정하는 함수 (정책 스냅샷)
     * @throws RejectException 위반 발견 시. 통과하면 정상 반환
     */
    public static void inspect(InputStream in, long zipSize, String displayName,
                               Predicate<String> blockedExtension, Config config) throws RejectException {
        long totalUncompressed = 0;
        int entryCount = 0;
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(in)) {
            ZipArchiveEntry entry;
            boolean any = false;
            while ((entry = zip.getNextEntry()) != null) {
                any = true;
                entryCount++;
                if (entryCount >= config.maxEntries()) {
                    throw new RejectException(ReasonCode.ARCHIVE_LIMIT_EXCEEDED,
                            displayName + " — 압축 파일의 항목 수 또는 해제 크기가 허용 범위를 넘습니다.",
                            "entries >= " + config.maxEntries());
                }
                if (entry.getGeneralPurposeBit().usesEncryption()) {
                    throw new RejectException(ReasonCode.ARCHIVE_ENCRYPTED,
                            displayName + " — 암호가 걸린 압축 파일은 내용을 확인할 수 없어 업로드할 수 없습니다.");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = FilenameProcessor.sanitize(entry.getName());
                FilenameInfo info = FilenameProcessor.parse(entryName);
                checkEntryName(displayName, entryName, info, blockedExtension);

                // 실제 읽은 바이트로 크기 측정. 상한을 넘는 순간 중단
                byte[] head = new byte[HEAD_BYTES];
                int headLen = readUpTo(zip, head);
                long entryBytes = headLen;
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    entryBytes += n;
                    if (totalUncompressed + entryBytes > config.maxTotalUncompressedBytes()) {
                        throw new RejectException(ReasonCode.ARCHIVE_LIMIT_EXCEEDED,
                                displayName + " — 압축 파일의 항목 수 또는 해제 크기가 허용 범위를 넘습니다.",
                                "uncompressed > " + config.maxTotalUncompressedBytes());
                    }
                }
                totalUncompressed += entryBytes;

                byte[] actualHead = headLen == head.length ? head : java.util.Arrays.copyOf(head, headLen);
                checkEntryContent(displayName, entryName, info, actualHead, entryBytes);
            }
            if (!any) {
                // 항목이 하나도 없으면 zip이 아니거나 빈 zip. 빈 zip은 위협이 아니므로 통과
                return;
            }
            if (zipSize > 0 && totalUncompressed / zipSize >= config.maxRatio()) {
                throw new RejectException(ReasonCode.ARCHIVE_LIMIT_EXCEEDED,
                        displayName + " — 압축 파일의 항목 수 또는 해제 크기가 허용 범위를 넘습니다.",
                        "ratio " + (totalUncompressed / Math.max(zipSize, 1)) + " >= " + config.maxRatio());
            }
        } catch (IOException e) {
            throw new RejectException(ReasonCode.ARCHIVE_UNSUPPORTED,
                    displayName + " — 압축 파일을 읽을 수 없습니다.", "zip read error: " + e.getMessage());
        }
    }

    private static void checkEntryName(String zipName, String entryName, FilenameInfo info,
                                       Predicate<String> blockedExtension) {
        if (info.hasExtension() && blockedExtension.test(info.extension())) {
            throw new RejectException(ReasonCode.BLOCKED_EXTENSION,
                    zipName + " — 압축 파일 안의 '" + entryName + "'이(가) 차단 대상 확장자입니다.");
        }
        for (String middle : info.middleSegments()) {
            if (blockedExtension.test(middle)) {
                throw new RejectException(ReasonCode.BLOCKED_INNER_EXTENSION,
                        zipName + " — 압축 파일 안의 '" + entryName + "' 파일명 중간의 ." + middle + "이(가) 차단 대상입니다.");
            }
        }
    }

    private static void checkEntryContent(String zipName, String entryName, FilenameInfo info,
                                          byte[] head, long entrySize) {
        Optional<String> executable = ExecutableSignatureDetector.detect(head, entrySize);
        if (executable.isPresent()) {
            throw new RejectException(ReasonCode.EXECUTABLE_DETECTED,
                    zipName + " — 압축 파일 안의 '" + entryName + "'이(가) 실행 파일입니다.",
                    "entry executable: " + executable.get());
        }
        // 중첩 판정: 확장자 또는 앞부분 시그니처. 문서 확장자는 예외 (5-5)
        String ext = info.extension();
        if (ext != null && DOCUMENT_EXCEPTIONS.contains(ext)) {
            return;
        }
        boolean nestedByExtension = ext != null && NESTED_ARCHIVE_EXTENSIONS.contains(ext);
        boolean nestedBySignature = looksLikeArchive(head);
        if (nestedByExtension || nestedBySignature) {
            throw new RejectException(ReasonCode.ARCHIVE_NESTED,
                    zipName + " — 압축 파일 안에 다른 압축 파일이 있어 업로드할 수 없습니다.");
        }
    }

    private static boolean looksLikeArchive(byte[] head) {
        return startsWith(head, 'P', 'K', 3, 4)                     // zip
                || startsWith(head, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C) // 7z
                || startsWith(head, 'R', 'a', 'r', '!')               // rar
                || startsWith(head, 0x1F, 0x8B);                      // gzip
    }

    private static boolean startsWith(byte[] data, int... expected) {
        if (data.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static int readUpTo(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }
}
