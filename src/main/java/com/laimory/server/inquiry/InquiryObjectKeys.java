package com.laimory.server.inquiry;

import com.laimory.server.timeline.photo.PhotoFilenames;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import java.util.UUID;

/**
 * 문의 첨부 객체의 S3 key 규칙 — 사진과 같은 subject namespace 아래 별도 {@code inquiries/} prefix다.
 *
 * <p>사진 namespace를 공유하는 이유는 탈퇴 삭제(#302)가 subject 단위 prefix 삭제로 첨부까지 같은 체계로
 * 지우기 위해서다. prefix를 나누는 이유는 사진 serving key 규칙({@code /photos/})과 CDN 서빙 경로를
 * 건드리지 않기 위해서다 — 첨부는 관리자만 presigned GET으로 본다.
 */
public final class InquiryObjectKeys {

    /** 문의 한 건의 첨부 상한 — 접수 body와 presign 발급 요청 둘 다 이 값으로 거른다. */
    public static final int MAX_ATTACHMENTS = 3;

    private static final String PREFIX_SEGMENT = "/inquiries/";

    private InquiryObjectKeys() {
    }

    /** {@code {subjectNamespace}/inquiries/} — 탈퇴 삭제가 통째로 비우는 prefix. */
    public static String subjectPrefix(UUID subjectId) {
        return PhotoObjectKeys.subjectNamespace(subjectId) + PREFIX_SEGMENT;
    }

    /** {@code {subjectNamespace}/inquiries/{filename}} — presign PUT·관리자 GET이 쓰는 full key. */
    public static String fullKey(String filename, UUID subjectId) {
        return subjectPrefix(subjectId) + filename;
    }

    /** 사진과 같은 {@code {uuidv7}.{ext}} 형식만 첨부 filename으로 인정한다. */
    public static boolean isValidFilename(String filename) {
        return PhotoFilenames.isValid(filename);
    }
}
