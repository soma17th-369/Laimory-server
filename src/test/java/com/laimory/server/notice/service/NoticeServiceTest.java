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
import com.laimory.server.notice.dto.PopupNoticeResponse;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.repository.NoticeRepository;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

/**
 * 공지 leaf service — 공개 목록·단건·팝업·관리자 목록 응답, 입력 규칙(제목·HTTPS URL·썸네일 파일명)과 관리자
 * 수정/노출/팝업/썸네일 전환 결과를 실 엔티티로 검증한다. 썸네일 URL 파생은 실 {@link NoticeThumbnailService}를 쓰고
 * 외부 경계인 S3 presigner만 mock이다.
 */
@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    private static final String URL = "https://www.laimory.app/notices/12";
    private static final String THUMBNAIL = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.webp";

    @Mock
    private NoticeRepository noticeRepository;

    @Mock
    private S3PhotoStorageService s3PhotoStorageService;

    private NoticeThumbnailService thumbnails;

    @BeforeEach
    void setUp() {
        thumbnails = new NoticeThumbnailService(s3PhotoStorageService, DataSize.ofMegabytes(10), "cdn.example");
    }

    @Test
    void findVisibleNoticesReturnsRepositoryOrder() {
        Notice newer = Notice.of("둘째", URL);
        Notice older = Notice.of("첫째", URL);
        when(noticeRepository.findByHiddenFalseOrderByNoticeIdDesc()).thenReturn(List.of(newer, older));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

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
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        List<AdminNoticeResponse> notices = service.findAllNotices();

        assertThat(notices).extracting(AdminNoticeResponse::title).containsExactly("숨김 공지", "노출 공지");
        assertThat(notices).extracting(AdminNoticeResponse::hidden).containsExactly(true, false);
    }

    @Test
    void findAllNoticesShowsThumbnailUrlOrNullWithoutThumbnail() {
        Notice withThumbnail = Notice.of("썸네일 있음", URL);
        withThumbnail.changeThumbnail(THUMBNAIL);
        Notice withoutThumbnail = Notice.of("썸네일 없음", URL);
        when(noticeRepository.findAllByOrderByNoticeIdDesc()).thenReturn(List.of(withThumbnail, withoutThumbnail));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        List<AdminNoticeResponse> notices = service.findAllNotices();

        assertThat(notices).extracting(AdminNoticeResponse::thumbnailUrl)
                .containsExactly("https://cdn.example/notices/" + THUMBNAIL, null);
    }

    @Test
    void registerStripsTitleKeepsUrlAndStartsVisible() {
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        service.register("  점검 안내  ", URL);

        ArgumentCaptor<Notice> captor = ArgumentCaptor.forClass(Notice.class);
        verify(noticeRepository).save(captor.capture());
        Notice saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("점검 안내");
        assertThat(saved.getContentUrl()).isEqualTo(URL);
        assertThat(saved.isHidden()).isFalse();
        assertThat(saved.isPopup()).isFalse();
    }

    @Test
    void registerRejectsBlankTitleBeforeSaving() {
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThatThrownBy(() -> service.register("   ", URL))
                .isInstanceOf(IllegalArgumentException.class);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    void registerRejectsNonHttpsHostlessOrOverlongUrlBeforeSaving() {
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

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
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        service.edit(3L, " 새 제목 ", "https://www.laimory.app/notices/12-r2");

        assertThat(notice.getTitle()).isEqualTo("새 제목");
        assertThat(notice.getContentUrl()).isEqualTo("https://www.laimory.app/notices/12-r2");
        assertThat(notice.isHidden()).isTrue();
    }

    @Test
    void editMissingNoticeIsNotFound() {
        when(noticeRepository.findByNoticeId(9L)).thenReturn(Optional.empty());
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThatThrownBy(() -> service.edit(9L, "제목", URL))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void changeVisibilityHidesAndRestoresTheSameRow() {
        Notice notice = Notice.of("제목", URL);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        service.changeVisibility(3L, true);
        assertThat(notice.isHidden()).isTrue();

        service.changeVisibility(3L, false);
        assertThat(notice.isHidden()).isFalse();
    }

    @Test
    void getVisibleNoticeReturnsTitleAndContentUrl() {
        Notice notice = Notice.of("점검 안내", URL);
        ReflectionTestUtils.setField(notice, "noticeId", 12L);
        when(noticeRepository.findByNoticeIdAndHiddenFalse(12L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        NoticeResponse response = service.getVisibleNotice("v1", 12L);

        assertThat(response.noticeId()).isEqualTo(12L);
        assertThat(response.title()).isEqualTo("점검 안내");
        assertThat(response.contentUrl()).isEqualTo(URL);
    }

    @Test
    void getVisibleNoticeHiddenOrMissingIsNotFound() {
        when(noticeRepository.findByNoticeIdAndHiddenFalse(9L)).thenReturn(Optional.empty());
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThatThrownBy(() -> service.getVisibleNotice("v1", 9L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void findPopupNoticesReturnsIdTitleAndThumbnailUrlInRepositoryOrder() {
        Notice newer = Notice.of("둘째 팝업", URL);
        ReflectionTestUtils.setField(newer, "noticeId", 15L);
        newer.changeThumbnail(THUMBNAIL);
        Notice older = Notice.of("첫째 팝업", URL);
        ReflectionTestUtils.setField(older, "noticeId", 12L);
        older.changeThumbnail("0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a7.jpg");
        when(noticeRepository.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc()).thenReturn(List.of(newer, older));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThat(service.findPopupNotices()).containsExactly(
                new PopupNoticeResponse(15L, "둘째 팝업", "https://cdn.example/notices/" + THUMBNAIL),
                new PopupNoticeResponse(12L, "첫째 팝업",
                        "https://cdn.example/notices/0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a7.jpg"));
    }

    @Test
    void findPopupNoticesWithoutPopupReturnsEmptyList() {
        when(noticeRepository.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc()).thenReturn(List.of());
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThat(service.findPopupNotices()).isEmpty();
    }

    @Test
    void changePopupDesignatesNoticeKeepingVisibility() {
        Notice notice = Notice.of("제목", URL);
        notice.changeThumbnail(THUMBNAIL);
        notice.changeVisibility(true);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        service.changePopup(3L, true);

        assertThat(notice.isPopup()).isTrue();
        assertThat(notice.isHidden()).isTrue();
    }

    @Test
    void changePopupWithoutThumbnailIsRejected() {
        Notice notice = Notice.of("제목", URL);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThatThrownBy(() -> service.changePopup(3L, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(notice.isPopup()).isFalse();
    }

    @Test
    void changePopupReleasesDesignatedNotice() {
        Notice notice = Notice.of("제목", URL);
        notice.changeThumbnail(THUMBNAIL);
        notice.changePopup(true);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        service.changePopup(3L, false);

        assertThat(notice.isPopup()).isFalse();
    }

    @Test
    void changePopupMissingNoticeIsNotFound() {
        when(noticeRepository.findByNoticeId(9L)).thenReturn(Optional.empty());
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThatThrownBy(() -> service.changePopup(9L, true))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }

    @Test
    void changeThumbnailStoresFilenameKeepingPopup() {
        Notice notice = Notice.of("제목", URL);
        notice.changeThumbnail(THUMBNAIL);
        notice.changePopup(true);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        service.changeThumbnail(3L, "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a7.png");

        assertThat(notice.getThumbnailFilename()).isEqualTo("0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a7.png");
        assertThat(notice.isPopup()).isTrue();
    }

    @Test
    void changeThumbnailRejectsPathLikeFilename() {
        Notice notice = Notice.of("제목", URL);
        when(noticeRepository.findByNoticeId(3L)).thenReturn(Optional.of(notice));
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        // 저장된 파일명은 notices/ 뒤에 그대로 붙어 CDN URL이 된다 — 발급 형식 밖의 값은 거절한다.
        assertThatThrownBy(() -> service.changeThumbnail(3L, "../photos/" + THUMBNAIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(notice.getThumbnailFilename()).isNull();
    }

    @Test
    void changeThumbnailMissingNoticeIsNotFound() {
        when(noticeRepository.findByNoticeId(9L)).thenReturn(Optional.empty());
        NoticeService service = new NoticeService(noticeRepository, thumbnails);

        assertThatThrownBy(() -> service.changeThumbnail(9L, THUMBNAIL))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getExceptionType())
                .isEqualTo(ExceptionType.RESOURCE_NOT_FOUND);
    }
}
