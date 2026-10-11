package com.laimory.server.notice.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.dto.AdminNoticeResponse;
import com.laimory.server.notice.dto.NoticeResponse;
import com.laimory.server.notice.dto.PopupNoticeResponse;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.repository.NoticeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지사항 leaf service — 공개 목록·단건(숨김 제외), 앱 시작 팝업, 관리자 등록·수정·노출·팝업 전환·썸네일 지정을 소유한다.
 *
 * <p>원문은 {@code contentUrl} page가 소유한다(약관 공개 조회와 같은 형태). 단건 조회는 앱이 팝업을 탭했을 때
 * 원문 URL을 받는 경로다(#553·#560). 읽기 경로는 SELECT 하나라 Spring transaction 없이
 * autocommit으로 실행한다(#499). 쓰기는 행 하나의 dirty checking이라 service 메서드 transaction 하나가 경계다.
 * 관리자 쓰기는 결과를 반환하지 않는다 — 관리자 웹이 쓰기 직후 목록을 다시 조회한다(조회가 단일 원천, #528).
 *
 * <p>앱 시작 팝업({@link #findPopupNotices})은 initializer가 요청마다 읽는 전역 값이라 공유 Redis 캐시를
 * 탄다(#491). 캐시 값은 id·제목·썸네일 URL이라(#560) 팝업 목록이나 그 제목·썸네일을 바꾸는 쓰기
 * ({@link #changePopup}·{@link #changeVisibility}·{@link #edit}·{@link #changeThumbnail})가 commit 뒤 evict한다 —
 * 등록만 팝업 해제 상태로 만들어 목록이 그대로다. 저장소·TTL·동시 miss 의미론은 {@code CacheConfig} 소유다.
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    /**
     * 값 shape가 id 목록에서 팝업 항목 목록으로 바뀌며(#560) 이름도 바꿨다 — 같은 이름이면 rolling 배포 중 구·신
     * 서버가 서로의 값을 읽어 원소 타입이 어긋난다({@code CacheConfig} 주석).
     */
    public static final String POPUP_CACHE_NAME = "notice:popup-notices";

    private static final String CACHE_MANAGER = "redisCacheManager";
    private static final String POPUP_CACHE_KEY = "'all'";

    private final NoticeRepository noticeRepository;
    private final NoticeThumbnailService noticeThumbnailService;

    /** 공개 목록 — 노출 중 공지 전체, 최신 순. 없으면 빈 목록이다. */
    public List<NoticeResponse> findVisibleNotices(String applicationVersion) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return noticeRepository.findByHiddenFalseOrderByNoticeIdDesc().stream()
                .map(NoticeResponse::from)
                .toList();
    }

    /** 공개 단건 — 숨김이거나 없으면 404. */
    public NoticeResponse getVisibleNotice(String applicationVersion, long noticeId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return noticeRepository.findByNoticeIdAndHiddenFalse(noticeId)
                .map(NoticeResponse::from)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
    }

    /**
     * 앱 시작 팝업 — 지정됐고 숨김이 아닌 공지의 id·제목·썸네일 URL, 최신 순. 없으면 빈 목록이다. 지정된 공지는
     * 항상 썸네일이 있다({@code Notice#changePopup}). 캐시 값이라 JSON으로 왕복 가능한 가변 목록으로 만든다
     * ({@code Stream.toList()}의 불변 구현은 역직렬화되지 않는다) — 호출자는 수정하지 않는다.
     */
    @Cacheable(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY, sync = true)
    public List<PopupNoticeResponse> findPopupNotices() {
        return noticeRepository.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc().stream()
                .map(notice -> new PopupNoticeResponse(notice.getNoticeId(), notice.getTitle(),
                        noticeThumbnailService.cdnUrl(notice.getThumbnailFilename())))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** 관리자 목록 — 숨김 포함 전체, 최신 순. */
    public List<AdminNoticeResponse> findAllNotices() {
        return noticeRepository.findAllByOrderByNoticeIdDesc().stream()
                .map(notice -> AdminNoticeResponse.of(notice, notice.getThumbnailFilename() == null
                        ? null : noticeThumbnailService.cdnUrl(notice.getThumbnailFilename())))
                .toList();
    }

    @Transactional
    public void register(String title, String contentUrl) {
        noticeRepository.save(Notice.of(title, contentUrl));
    }

    /** 팝업 항목에 제목이 실리므로 팝업 캐시를 지운다. */
    @Transactional
    @CacheEvict(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY)
    public void edit(long noticeId, String title, String contentUrl) {
        requireNotice(noticeId).edit(title, contentUrl);
    }

    /** 숨기면 팝업 목록에서 빠지고 재노출하면 돌아오므로 팝업 캐시를 지운다. */
    @Transactional
    @CacheEvict(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY)
    public void changeVisibility(long noticeId, boolean hidden) {
        requireNotice(noticeId).changeVisibility(hidden);
    }

    /** 이 공지만 팝업 지정·해제한다 — 다른 공지의 지정과 노출 상태는 건드리지 않는다. 지정은 썸네일이 있어야 한다. */
    @Transactional
    @CacheEvict(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY)
    public void changePopup(long noticeId, boolean popup) {
        requireNotice(noticeId).changePopup(popup);
    }

    /**
     * 썸네일 교체 — 관리자 웹이 S3 PUT 성공 뒤 부른다. S3 실존은 확인하지 않고 이전 객체는 지우지 않는다.
     * 팝업 항목에 썸네일 URL이 실리므로 팝업 캐시를 지운다.
     */
    @Transactional
    @CacheEvict(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY)
    public void changeThumbnail(long noticeId, String thumbnailFilename) {
        requireNotice(noticeId).changeThumbnail(thumbnailFilename);
    }

    private Notice requireNotice(long noticeId) {
        return noticeRepository.findByNoticeId(noticeId)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
    }
}
