package com.laimory.server.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.repository.NoticeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 공지 leaf service — 공개 404 은닉·입력 규칙·관리자 수정/노출 전환 결과를 실 엔티티로 검증한다. */
@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    @Mock
    private NoticeRepository noticeRepository;

    @Test
    void findVisibleNoticesReturnsRepositoryOrderWithoutHiddenFilteringInService() {
        Notice newer = Notice.of("둘째", "본문");
        Notice older = Notice.of("첫째", "본문");
        when(noticeRepository.findByHiddenFalseOrderByNoticeIdDesc()).thenReturn(List.of(newer, older));
        NoticeService service = new NoticeService(noticeRepository);

        List<Notice> notices = service.findVisibleNotices("v1");

        assertThat(notices).containsExactly(newer, older);
    }

    @Test
    void getVisibleNoticeHidesMissingAndHiddenAsNotFound() {
        when(noticeRepository.findByNoticeIdAndHiddenFalse(7L)).thenReturn(Optional.empty());
        NoticeService service = new NoticeService(noticeRepository);

        assertThatThrownBy(() -> service.getVisibleNotice("v1", 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void registerStripsTitleAndStartsVisible() {
        when(noticeRepository.save(any(Notice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NoticeService service = new NoticeService(noticeRepository);

        Notice saved = service.register("  점검 안내  ", "내일 새벽 점검\n두 줄째");

        assertThat(saved.getTitle()).isEqualTo("점검 안내");
        assertThat(saved.getBody()).isEqualTo("내일 새벽 점검\n두 줄째");
        assertThat(saved.isHidden()).isFalse();
    }

    @Test
    void registerRejectsBlankTitleBeforeSaving() {
        NoticeService service = new NoticeService(noticeRepository);

        assertThatThrownBy(() -> service.register("   ", "본문"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    void registerRejectsBodyOverLimitBeforeSaving() {
        NoticeService service = new NoticeService(noticeRepository);

        assertThatThrownBy(() -> service.register("제목", "가".repeat(Notice.BODY_MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    void editReplacesTitleAndBodyKeepingVisibility() {
        Notice notice = Notice.of("이전 제목", "이전 본문");
        notice.changeVisibility(true);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository);

        Notice edited = service.edit(3L, " 새 제목 ", "새 본문");

        assertThat(edited).isSameAs(notice);
        assertThat(notice.getTitle()).isEqualTo("새 제목");
        assertThat(notice.getBody()).isEqualTo("새 본문");
        assertThat(notice.isHidden()).isTrue();
    }

    @Test
    void editMissingNoticeIsNotFound() {
        when(noticeRepository.findByNoticeId(9L)).thenReturn(Optional.empty());
        NoticeService service = new NoticeService(noticeRepository);

        assertThatThrownBy(() -> service.edit(9L, "제목", "본문"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void changeVisibilityHidesAndRestoresTheSameRow() {
        Notice notice = Notice.of("제목", "본문");
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository);

        service.changeVisibility(3L, true);
        assertThat(notice.isHidden()).isTrue();

        service.changeVisibility(3L, false);
        assertThat(notice.isHidden()).isFalse();
    }
}
