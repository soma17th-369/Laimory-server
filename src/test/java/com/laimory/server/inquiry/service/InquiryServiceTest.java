package com.laimory.server.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.inquiry.entity.Inquiry;
import com.laimory.server.inquiry.entity.InquiryAttachment;
import com.laimory.server.inquiry.repository.InquiryAttachmentRepository;
import com.laimory.server.inquiry.repository.InquiryRepository;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 문의 leaf service — 접수 시 첨부 검증·순서, 관리자 열람·처리됨 시각, 탈퇴 삭제 순서를 실 엔티티로 검증한다. */
@ExtendWith(MockitoExtension.class)
class InquiryServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(18L);
    private static final String FILENAME_A = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.jpg";
    private static final String FILENAME_B = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a7.png";
    /** UTC 고정 Clock — 처리 시각은 Clock zone과 무관하게 KST 벽시계로 기록돼야 한다. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T01:30:00Z"), ZoneOffset.UTC);

    @Mock
    private InquiryRepository inquiryRepository;
    @Mock
    private InquiryAttachmentRepository inquiryAttachmentRepository;

    private InquiryService service() {
        return new InquiryService(inquiryRepository, inquiryAttachmentRepository, CLOCK);
    }

    @Test
    void registerSavesInquiryThenAttachmentsInRequestOrder() {
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> {
            Inquiry saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "inquiryId", 11L);
            return saved;
        });

        Inquiry inquiry = service().register("v1", SUBJECT_ID, " user@example.com ",
                "앱이 멈춰요", List.of(FILENAME_A, FILENAME_B));

        assertThat(inquiry.getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(inquiry.getEmail()).isEqualTo("user@example.com");
        assertThat(inquiry.getBody()).isEqualTo("앱이 멈춰요");
        assertThat(inquiry.isAnswered()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InquiryAttachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(inquiryAttachmentRepository).saveAll(attachments.capture());
        assertThat(attachments.getValue()).extracting(InquiryAttachment::getInquiryId).containsOnly(11L);
        assertThat(attachments.getValue()).extracting(InquiryAttachment::getFilename)
                .containsExactly(FILENAME_A, FILENAME_B);
        assertThat(attachments.getValue()).extracting(InquiryAttachment::getPosition).containsExactly(0, 1);
    }

    @Test
    void registerWithoutAttachmentsSavesNoAttachmentRows() {
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().register("v1", SUBJECT_ID, "u@example.com", "본문", null);

        verify(inquiryAttachmentRepository).saveAll(List.of());
    }

    @Test
    void registerRejectsInvalidDuplicateOrTooManyFilenamesBeforeSaving() {
        InquiryService service = service();

        assertThatThrownBy(() -> service.register("v1", SUBJECT_ID, "u@example.com", "본문",
                List.of("../etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("v1", SUBJECT_ID, "u@example.com", "본문",
                List.of(FILENAME_A, FILENAME_A)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("v1", SUBJECT_ID, "u@example.com", "본문",
                List.of(FILENAME_A, FILENAME_B, FILENAME_A.replace("a6.jpg", "a8.webp"),
                        FILENAME_A.replace("a6.jpg", "a9.jpg"))))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.PHOTO_COUNT_EXCEEDED);
        verify(inquiryRepository, never()).save(any());
        verify(inquiryAttachmentRepository, never()).saveAll(anyList());
    }

    @Test
    void registerRejectsBlankBodyAndOverlongEmailBeforeSaving() {
        InquiryService service = service();

        assertThatThrownBy(() -> service.register("v1", SUBJECT_ID, "u@example.com", "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("v1", SUBJECT_ID,
                "u".repeat(Inquiry.EMAIL_MAX_LENGTH) + "@example.com", "본문", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    void findAllReturnsInquiriesNewestFirstWithoutReadingAttachments() {
        Inquiry newer = inquiry(12L);
        Inquiry older = inquiry(7L);
        when(inquiryRepository.findAllByOrderByInquiryIdDesc()).thenReturn(List.of(newer, older));

        assertThat(service().findAll()).containsExactly(newer, older);
        verifyNoInteractions(inquiryAttachmentRepository);
    }

    @Test
    void getPairsInquiryWithItsAttachmentFilenamesInPositionOrder() {
        Inquiry inquiry = inquiry(12L);
        when(inquiryRepository.findByInquiryId(12L)).thenReturn(Optional.of(inquiry));
        when(inquiryAttachmentRepository.findByInquiryIdOrderByPositionAsc(12L)).thenReturn(List.of(
                InquiryAttachment.of(12L, FILENAME_A, 0),
                InquiryAttachment.of(12L, FILENAME_B, 1)));

        InquiryService.InquiryWithAttachments item = service().get(12L);

        assertThat(item.inquiry()).isSameAs(inquiry);
        assertThat(item.attachmentFilenames()).containsExactly(FILENAME_A, FILENAME_B);
    }

    @Test
    void getMissingInquiryIsNotFound() {
        when(inquiryRepository.findByInquiryId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void changeAnsweredRecordsKstWallClockAndClearsOnFalse() {
        Inquiry inquiry = inquiry(5L);
        when(inquiryRepository.findByInquiryId(5L)).thenReturn(Optional.of(inquiry));
        InquiryService service = service();

        service.changeAnswered(5L, true);
        // 01:30Z는 KST 10:30 — UTC Clock을 주입해도 KST 벽시계로 기록된다.
        assertThat(inquiry.getAnsweredAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 10, 30));

        service.changeAnswered(5L, false);
        assertThat(inquiry.getAnsweredAt()).isNull();
        assertThat(inquiry.isAnswered()).isFalse();
    }

    @Test
    void deleteAllBySubjectRemovesAttachmentsBeforeInquiries() {
        service().deleteAllBySubjectId(SUBJECT_ID);

        InOrder order = inOrder(inquiryAttachmentRepository, inquiryRepository);
        order.verify(inquiryAttachmentRepository).deleteAllBySubjectId(SUBJECT_ID);
        order.verify(inquiryRepository).deleteAllBySubjectId(SUBJECT_ID);
    }

    private static Inquiry inquiry(long inquiryId) {
        Inquiry inquiry = Inquiry.of(SUBJECT_ID, "u@example.com", "본문");
        ReflectionTestUtils.setField(inquiry, "inquiryId", inquiryId);
        return inquiry;
    }
}
