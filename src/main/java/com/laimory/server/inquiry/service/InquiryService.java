package com.laimory.server.inquiry.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.inquiry.InquiryObjectKeys;
import com.laimory.server.inquiry.entity.Inquiry;
import com.laimory.server.inquiry.entity.InquiryAttachment;
import com.laimory.server.inquiry.repository.InquiryAttachmentRepository;
import com.laimory.server.inquiry.repository.InquiryRepository;
import com.laimory.server.terms.TermTimes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문의 leaf service — 접수(문의 행 + 첨부 행 한 transaction), 관리자 열람·처리됨 표시, 탈퇴 삭제를 소유한다.
 *
 * <p>문의와 첨부는 한 owner의 두 테이블이라 이 service가 두 repository를 함께 감싼다(연관 매핑 없이
 * plain FK). 읽기 경로는 SELECT만이라 Spring transaction 없이 autocommit으로 실행한다(#499).
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final InquiryAttachmentRepository inquiryAttachmentRepository;
    private final Clock clock;

    /** 관리자 목록 항목 — 문의 행과 첨부 filename(요청 순서). */
    public record InquiryWithAttachments(Inquiry inquiry, List<String> attachmentFilenames) { }

    /**
     * 앱 인증 접수. 첨부 filename은 presign 발급 형식({@code uuidv7.ext})·개수·중복만 검증하고 S3 실존은
     * 확인하지 않는다(계획의 승인된 결정 — 요청 body 밖 상태에 의존하지 않는다).
     */
    @Transactional
    public Inquiry register(String applicationVersion, UUID subjectId, String email, String body,
                            List<String> attachmentFilenames) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        List<String> filenames = validateAttachmentFilenames(attachmentFilenames);
        Inquiry inquiry = inquiryRepository.save(Inquiry.of(subjectId, email, body));
        List<InquiryAttachment> attachments = new ArrayList<>(filenames.size());
        for (int position = 0; position < filenames.size(); position++) {
            attachments.add(InquiryAttachment.of(inquiry.getInquiryId(), filenames.get(position), position));
        }
        inquiryAttachmentRepository.saveAll(attachments);
        return inquiry;
    }

    /** 관리자 목록 — 전체, 최신 순, 첨부 filename 포함. */
    public List<InquiryWithAttachments> findAll() {
        List<Inquiry> inquiries = inquiryRepository.findAllByOrderByInquiryIdDesc();
        if (inquiries.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> filenamesByInquiryId = inquiryAttachmentRepository
                .findByInquiryIdIn(inquiries.stream().map(Inquiry::getInquiryId).toList()).stream()
                .sorted((left, right) -> Integer.compare(left.getPosition(), right.getPosition()))
                .collect(Collectors.groupingBy(InquiryAttachment::getInquiryId,
                        Collectors.mapping(InquiryAttachment::getFilename, Collectors.toList())));
        return inquiries.stream()
                .map(inquiry -> new InquiryWithAttachments(inquiry,
                        filenamesByInquiryId.getOrDefault(inquiry.getInquiryId(), List.of())))
                .toList();
    }

    /** 관리자 상세 — 없으면 404. */
    public InquiryWithAttachments get(long inquiryId) {
        Inquiry inquiry = requireInquiry(inquiryId);
        List<String> filenames = inquiryAttachmentRepository.findByInquiryIdOrderByPositionAsc(inquiryId).stream()
                .map(InquiryAttachment::getFilename)
                .toList();
        return new InquiryWithAttachments(inquiry, filenames);
    }

    /** 처리됨 표시(답장 발송 후)·해제. 표시 시각은 서버가 캡처한 KST 벽시계다. */
    @Transactional
    public Inquiry changeAnswered(long inquiryId, boolean answered) {
        Inquiry inquiry = requireInquiry(inquiryId);
        inquiry.changeAnswered(answered ? TermTimes.kstWallClock(clock.instant()) : null);
        return inquiry;
    }

    /**
     * 계정 삭제(#302) — 첨부 행을 먼저, 문의 행(email PII)을 그다음에 지운다(FK RESTRICT 순서).
     * 미존재는 0행(멱등)이며 호출자 transaction에 합류한다. S3 첨부 객체는 subject prefix 삭제가 담당한다.
     */
    public void deleteAllBySubjectId(UUID subjectId) {
        inquiryAttachmentRepository.deleteAllBySubjectId(subjectId);
        inquiryRepository.deleteAllBySubjectId(subjectId);
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
