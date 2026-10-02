package com.laimory.server.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.dto.AdminNoticeResponse;
import com.laimory.server.notice.dto.NoticeResponse;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.repository.NoticeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 공지 leaf service — 공개·관리자 목록 응답, 입력 규칙(제목·HTTPS URL)과 관리자 수정/노출 전환 결과를 실 엔티티로 검증한다. */
@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    private static final String URL = "https://www.laimory.app/notices/12";

    @Mock
    private NoticeRepository noticeRepository;

    @Test
    void findVisibleNoticesReturnsRepositoryOrder() {
        Notice newer = Notice.of("둘째", URL);
        Notice older = Notice.of("첫째", URL);
        when(noticeRepository.findByHiddenFalseOrderByNoticeIdDesc()).thenReturn(List.of(newer, older));
        NoticeService service = new NoticeService(noticeRepository);

        List<NoticeResponse> notices = service.findVisibleNotices("v1");

        assertThat(notices).extracting(NoticeResponse::title).containsExactly("둘째", "첫째");
        assertThat(notices).extracting(NoticeResponse::contentUrl).containsOnly(URL);
    }

    @Test
    void findAllNoticesIncludesHiddenWithVisibilityState() {
        Notice hidden = Notice.of("숨김 공지", URL);
        hidden.changeVisibility(true);
        Notice visible = Notice.of("노출 공지", URL);
        when(noticeRepository.findAllByOrderByNoticeIdDesc()).thenReturn(List.of(hidden, visible));
        NoticeService service = new NoticeService(noticeRepository);

        List<AdminNoticeResponse> notices = service.findAllNotices();

        assertThat(notices).extracting(AdminNoticeResponse::title).containsExactly("숨김 공지", "노출 공지");
        assertThat(notices).extracting(AdminNoticeResponse::hidden).containsExactly(true, false);
    }

    @Test
    void registerStripsTitleKeepsUrlAndStartsVisible() {
        NoticeService service = new NoticeService(noticeRepository);

        service.register("  점검 안내  ", URL);

        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeRepository).save(captor.capture());
        Notice saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("점검 안내");
        assertThat(saved.getContentUrl()).isEqualTo(URL);
        assertThat(saved.isHidden()).isFalse();
    }

    @Test
    void registerRejectsBlankTitleBeforeSaving() {
        NoticeService service = new NoticeService(noticeRepository);

        assertThatThrownBy(() -> service.register("   ", URL))
                .isInstanceOf(IllegalArgumentException.class);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    void registerRejectsNonHttpsHostlessOrOverlongUrlBeforeSaving() {
        NoticeService service = new NoticeService(noticeRepository);

        // 약관 등록과 같은 기준 — host가 있는 절대 HTTPS만 게시 주소로 인정한다.
        assertThatThrownBy(() -> service.register("제목", "http://www.laimory.app/notices/12"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("제목", "https:///notices/12"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("제목", "/notices/12"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("제목",
                "https://www.laimory.app/" + "a".repeat(Notice.CONTENT_URL_MAX_LENGTH)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    void editReplacesTitleAndUrlKeepingVisibility() {
        Notice notice = Notice.of("이전 제목", URL);
        notice.changeVisibility(true);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository);

        service.edit(3L, " 새 제목 ", "https://www.laimory.app/notices/12-r2");

        assertThat(notice.getTitle()).isEqualTo("새 제목");
        assertThat(notice.getContentUrl()).isEqualTo("https://www.laimory.app/notices/12-r2");
        assertThat(notice.isHidden()).isTrue();
    }

    @Test
    void editMissingNoticeIsNotFound() {
        when(noticeRepository.findByNoticeId(9L)).thenReturn(Optional.empty());
        NoticeService service = new NoticeService(noticeRepository);

        assertThatThrownBy(() -> service.edit(9L, "제목", URL))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void changeVisibilityHidesAndRestoresTheSameRow() {
        Notice notice = Notice.of("제목", URL);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository);

        service.changeVisibility(3L, true);
        assertThat(notice.isHidden()).isTrue();

        service.changeVisibility(3L, false);
        assertThat(notice.isHidden()).isFalse();
    }
}
