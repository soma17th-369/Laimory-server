package com.laimory.server.inquiry.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.inquiry.InquiryObjectKeys;
import com.laimory.server.inquiry.dto.AdminInquiryAttachmentResponse;
import com.laimory.server.inquiry.dto.AdminInquiryDetailResponse;
import com.laimory.server.inquiry.dto.AdminInquiryResponse;
import com.laimory.server.inquiry.dto.InquiryDetailResponse;
import com.laimory.server.inquiry.dto.InquirySummaryResponse;
import com.laimory.server.inquiry.entity.Inquiry;
import com.laimory.server.inquiry.entity.InquiryAttachment;
import com.laimory.server.inquiry.repository.InquiryAttachmentRepository;
import com.laimory.server.inquiry.repository.InquiryRepository;
import com.laimory.server.terms.TermTimes;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문의 service — 접수(문의 행 + 첨부 행 한 transaction), 소유자·관리자 열람, 처리됨 표시, 탈퇴 삭제를 소유한다.
 *
 * <p>문의와 첨부는 한 owner의 두 테이블이라 이 service가 두 repository를 함께 감싼다(연관 매핑 없이
 * plain FK). 상세 열람은 첨부 열람 URL 조립을 {@link InquiryAttachmentService}에 맡겨 응답을 완성한다(#528).
 * 읽기 경로는 SELECT만이라 Spring transaction 없이 autocommit으로 실행한다(#499). 쓰기는 결과를 반환하지
 * 않는다 — 앱은 접수 성공만, 관리자 웹은 쓰기 직후 목록 재조회로 상태를 확인한다(조회가 단일 원천).
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final InquiryAttachmentRepository inquiryAttachmentRepository;
    private final InquiryAttachmentService inquiryAttachmentService;
    private final Clock clock;

    /** 앱 "내 문의" 목록 상한(#529) — 최신 순이라 넘치면 가장 오래된 문의부터 빠진다. 페이지네이션은 없다. */
    static final int MY_INQUIRIES_LIMIT = 50;

    /**
     * 앱 인증 접수. 첨부 filename은 presign 발급 형식({@code uuidv7.ext})·개수·중복만 검증하고 S3 실존은
     * 확인하지 않는다(계획의 승인된 결정 — 요청 body 밖 상태에 의존하지 않는다).
     */
    @Transactional
    public void register(String applicationVersion, UUID subjectId, String email, String title,
                         String description, List<String> attachmentFilenames) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        List<String> filenames = validateAttachmentFilenames(attachmentFilenames);
        Inquiry inquiry = inquiryRepository.save(Inquiry.of(subjectId, email, title, description));
        inquiryAttachmentRepository.saveAll(filenames.stream()
                .map(filename -> InquiryAttachment.of(inquiry.getInquiryId(), filename))
                .toList());
    }

    /** 앱 "내 문의" 목록 — owner subject의 문의만, 최신 순 최대 {@value #MY_INQUIRIES_LIMIT}건. 첨부는 읽지 않는다. */
    public List<InquirySummaryResponse> findMine(String applicationVersion, UUID subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return inquiryRepository.findBySubjectIdOrderByInquiryIdDesc(subjectId,
                        PageRequest.of(0, MY_INQUIRIES_LIMIT)).stream()
                .map(InquirySummaryResponse::from)
                .toList();
    }

    /** 앱 "내 문의" 상세 — 없음과 비소유 모두 404로 존재를 숨긴다. */
    public InquiryDetailResponse getMine(String applicationVersion, UUID subjectId, long inquiryId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        Inquiry inquiry = inquiryRepository.findByInquiryIdAndSubjectId(inquiryId, subjectId)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
        List<String> attachmentUrls = attachmentFilenames(inquiryId).stream()
                .map(filename -> inquiryAttachmentService.cdnUrl(subjectId, filename))
                .toList();
        return InquiryDetailResponse.of(inquiry, attachmentUrls);
    }

    /** 관리자 목록 — 전체, 최신 순. 첨부는 상세에서만 필요하다. */
    public List<AdminInquiryResponse> findAll() {
        return inquiryRepository.findAllByOrderByInquiryIdDesc().stream()
                .map(AdminInquiryResponse::from)
                .toList();
    }

    /** 관리자 상세 + 첨부 열람 URL(앱 소유자와 같은 무서명 CDN URL, #529) — 없으면 404. */
    public AdminInquiryDetailResponse get(long inquiryId) {
        Inquiry inquiry = requireInquiry(inquiryId);
        List<AdminInquiryAttachmentResponse> attachments = attachmentFilenames(inquiryId).stream()
                .map(filename -> new AdminInquiryAttachmentResponse(filename,
                        inquiryAttachmentService.cdnUrl(inquiry.getSubjectId(), filename)))
                .toList();
        return new AdminInquiryDetailResponse(AdminInquiryResponse.from(inquiry), attachments);
    }

    /** 처리됨 표시(답장 발송 후)·해제. 표시 시각은 서버가 캡처한 KST 벽시계다. */
    @Transactional
    public void changeAnswered(long inquiryId, boolean answered) {
        requireInquiry(inquiryId).changeAnswered(answered ? TermTimes.kstWallClock(clock.instant()) : null);
    }

    /**
     * 계정 삭제(#302) — 첨부 행을 먼저, 문의 행(email PII)을 그다음에 지운다(FK RESTRICT 순서).
     * 미존재는 0행(멱등)이며 호출자 transaction에 합류한다. S3 첨부 객체는 subject prefix 삭제가 담당한다.
     */
    public void deleteAllBySubjectId(UUID subjectId) {
        inquiryAttachmentRepository.deleteAllBySubjectId(subjectId);
        inquiryRepository.deleteAllBySubjectId(subjectId);
    }

    private List<String> attachmentFilenames(long inquiryId) {
        return inquiryAttachmentRepository.findByInquiryIdOrderByInquiryAttachmentIdAsc(inquiryId).stream()
                .map(InquiryAttachment::getFilename)
                .toList();
    }

    private Inquiry requireInquiry(long inquiryId) {
        return inquiryRepository.findByInquiryId(inquiryId)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
    }

    private static List<String> validateAttachmentFilenames(List<String> attachmentFilenames) {
        if (attachmentFilenames == null || attachmentFilenames.isEmpty()) {
            return List.of();
        }
        if (attachmentFilenames.size() > InquiryObjectKeys.MAX_ATTACHMENTS) {
            throw new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, InquiryObjectKeys.MAX_ATTACHMENTS);
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < attachmentFilenames.size(); i++) {
            String filename = attachmentFilenames.get(i);
            if (!InquiryObjectKeys.isValidFilename(filename)) {
                throw new IllegalArgumentException("attachmentFilenames[" + i + "] must be a presigned filename");
            }
            if (!seen.add(filename)) {
                throw new IllegalArgumentException("attachmentFilenames[" + i + "] is duplicated");
            }
        }
        return List.copyOf(attachmentFilenames);
    }
}
