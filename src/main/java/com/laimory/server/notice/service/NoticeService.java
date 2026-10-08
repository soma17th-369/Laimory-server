package com.laimory.server.notice.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.dto.AdminNoticeResponse;
import com.laimory.server.notice.dto.NoticeResponse;
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
 * 공지사항 leaf service — 공개 목록·단건(숨김 제외), 앱 시작 팝업 id, 관리자 등록·수정·노출·팝업 전환을 소유한다.
 *
 * <p>원문은 {@code contentUrl} page가 소유한다(약관 공개 조회와 같은 형태). 단건 조회는 앱 시작 팝업이
 * 이니셜라이저가 준 id로 제목·URL을 받는 경로다(#553). 읽기 경로는 SELECT 하나라 Spring transaction 없이
 * autocommit으로 실행한다(#499). 쓰기는 행 하나의 dirty checking이라 service 메서드 transaction 하나가 경계다.
 * 관리자 쓰기는 결과를 반환하지 않는다 — 관리자 웹이 쓰기 직후 목록을 다시 조회한다(조회가 단일 원천, #528).
 *
 * <p>앱 시작 팝업 id({@link #findPopupNoticeIds})는 initializer가 요청마다 읽는 전역 값이라 공유 Redis 캐시를
 * 탄다(#491). 캐시 값은 id뿐이고 제목·URL은 캐시하지 않는 단건 조회가 소유하므로, id 목록을 바꾸는 쓰기
 * ({@link #changePopup}·{@link #changeVisibility})만 commit 뒤 evict한다 — 등록은 팝업 해제 상태로 만들고 수정은
 * 제목·URL만 바꿔 목록이 그대로다. 저장소·TTL·동시 miss 의미론은 {@code CacheConfig} 소유다.
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    public static final String POPUP_CACHE_NAME = "notice:popup";

    private static final String CACHE_MANAGER = "redisCacheManager";
    private static final String POPUP_CACHE_KEY = "'all'";

    private final NoticeRepository noticeRepository;

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
     * 앱 시작 팝업 공지 id — 지정됐고 숨김이 아닌 공지, 최신 순. 없으면 빈 목록이다. 캐시 값이라 JSON으로 왕복
     * 가능한 가변 목록으로 만든다({@code Stream.toList()}의 불변 구현은 역직렬화되지 않는다) — 호출자는 수정하지 않는다.
     */
    @Cacheable(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY, sync = true)
    public List<Long> findPopupNoticeIds() {
        return noticeRepository.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc().stream()
                .map(Notice::getNoticeId)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** 관리자 목록 — 숨김 포함 전체, 최신 순. */
    public List<AdminNoticeResponse> findAllNotices() {
        return noticeRepository.findAllByOrderByNoticeIdDesc().stream()
                .map(AdminNoticeResponse::from)
                .toList();
    }

    @Transactional
    public void register(String title, String contentUrl) {
        noticeRepository.save(Notice.of(title, contentUrl));
    }

    @Transactional
    public void edit(long noticeId, String title, String contentUrl) {
        requireNotice(noticeId).edit(title, contentUrl);
    }

    /** 숨기면 팝업 목록에서 빠지고 재노출하면 돌아오므로 팝업 캐시를 지운다. */
    @Transactional
    @CacheEvict(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY)
    public void changeVisibility(long noticeId, boolean hidden) {
        requireNotice(noticeId).changeVisibility(hidden);
    }

    /** 이 공지만 팝업 지정·해제한다 — 다른 공지의 지정과 노출 상태는 건드리지 않는다. */
    @Transactional
    @CacheEvict(cacheNames = POPUP_CACHE_NAME, cacheManager = CACHE_MANAGER, key = POPUP_CACHE_KEY)
    public void changePopup(long noticeId, boolean popup) {
        requireNotice(noticeId).changePopup(popup);
    }

    private Notice requireNotice(long noticeId) {
        return noticeRepository.findByNoticeId(noticeId)
                .orElseThrow(() -> new BusinessException(ExceptionType.RESOURCE_NOT_FOUND));
    }
}
