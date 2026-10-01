package com.study.fileupload.upload;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * PDF 검사 (명세 5-4). 객체 구조를 해석하지 않고 원본 바이트에서 이름 토큰만 검색한다.
 * (신뢰할 수 없는 파일을 복잡한 파서에 넣으면 파서가 공격 대상이 되므로 PDFBox 등을 쓰지 않음)
 *
 * - 이름 인코딩 우회: /J#61vaScript = /JavaScript → 이름 안의 #xx를 디코딩 후 비교
 * - 토큰 경계: 이름은 구분자에서 끝나므로 /JS가 /JSON에 매칭되지 않음
 * - 파일 전체를 스트림으로 읽으며 검색 (메모리에 통째로 올리지 않음)
 */
public final class PdfInspector {

    /** PC에서 열 때 위험 → 거부 (attachment로 낮춰도 다운로드 후 실행은 못 막음) */
    private static final Set<String> REJECT_NAMES = Set.of("Launch", "EmbeddedFile", "EmbeddedFiles");
    /** 브라우저 inline만 위험하거나 내용 확인 불가 → attachment로 낮춤 */
    private static final Set<String> DOWNGRADE_NAMES = Set.of("JavaScript", "JS", "AA", "Encrypt", "ObjStm");
    private static final String OPEN_ACTION = "OpenAction";
    private static final int MAX_NAME_LENGTH = 64;

    private PdfInspector() {
    }

    public record Result(boolean reject, boolean downgrade, boolean openAction, Set<String> foundNames) {
    }

    public static Result scan(InputStream rawIn) throws IOException {
        BufferedInputStream in = new BufferedInputStream(rawIn, 64 * 1024);
        Set<String> found = new HashSet<>();
        boolean reject = false;
        boolean downgrade = false;
        boolean openAction = false;

        int c;
        while ((c = in.read()) != -1) {
            if (c != '/') {
                continue;
            }
            String name = readName(in);
            if (name == null) {
                continue;
            }
            if (REJECT_NAMES.contains(name)) {
                found.add(name);
                reject = true;
                break;   // 거부가 확정되면 더 읽을 필요 없음
            }
            if (DOWNGRADE_NAMES.contains(name)) {
                found.add(name);
                downgrade = true;
            } else if (OPEN_ACTION.equals(name)) {
                found.add(name);
                openAction = true;   // 단독으로는 정상 PDF에도 흔함 → 로그만 (5-4)
            }
        }
        return new Result(reject, downgrade, openAction, found);
    }

    /**
     * '/' 다음의 이름을 읽는다. #xx는 디코딩한다.
     * PDF 구분자(공백류, ()<>[]{}/%)를 만나면 이름이 끝난다 — 토큰 경계는 여기서 보장된다.
     * 구분자로 끝났을 때 그 문자를 다시 처리해야 하는 경우('/'의 연속 등)를 위해 mark/reset 사용.
     */
    private static String readName(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() <= MAX_NAME_LENGTH) {
            in.mark(1);
            int c = in.read();
            if (c == -1 || isDelimiter(c)) {
                if (c == '/' || c == '%' || c == '(' || c == '<' || c == '[' || c == '{'
                        || c == ')' || c == '>' || c == ']' || c == '}') {
                    in.reset();   // 다음 토큰 시작일 수 있으므로 돌려놓음
                }
                break;
            }
            if (c == '#') {
                int h1 = in.read();
                int h2 = in.read();
                int decoded = decodeHexPair(h1, h2);
                if (decoded < 0) {
                    return null;   // 잘못된 #xx — 유효한 이름이 아님
                }
                sb.append((char) decoded);
            } else {
                sb.append((char) c);
            }
        }
        return sb.length() == 0 || sb.length() > MAX_NAME_LENGTH ? null : sb.toString();
    }

    private static boolean isDelimiter(int c) {
        return c == 0 || c == '\t' || c == '\n' || c == '\f' || c == '\r' || c == ' '
                || c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == '/' || c == '%';
    }

    private static int decodeHexPair(int h1, int h2) {
        int d1 = Character.digit(h1, 16);
        int d2 = Character.digit(h2, 16);
        return (d1 < 0 || d2 < 0) ? -1 : (d1 << 4) | d2;
    }
}
