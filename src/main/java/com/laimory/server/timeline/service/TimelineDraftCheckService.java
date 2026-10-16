package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.CheckDraftResponse;
import com.laimory.server.timeline.dto.CheckDraftSourceItem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 타임라인 생성 가능 확인. 데이터가 너무 적은 날은 AI가 의미 있는 Event를 만들지 못하므로, 클라가 생성 버튼을
 * 비활성화할 수 있게 받은 source item만으로 생성 가능 여부를 판정한다(#594).
 *
 * <p>통과 조건 중 하나라도 맞으면 생성 가능하다: PHOTO·CALENDAR 1개 이상, 위치 시간 30분 초과, NOTIFICATION
 * 3건 이상, 위치 시간 20분 이상이면서 NOTIFICATION 1건 이상. 위치 시간은 STAY·MOVEMENT의 {@code endAt − startAt}
 * 합이며 endAt이 없거나 startAt보다 앞서면 0으로 센다. HEALTH는 판정에 쓰지 않는다.
 *
 * <p>판정은 받은 목록만 본다 — 권한 상태·같은 날 이미 저장된 item(append)·SAVED 여부·크레딧은 보지 않으며,
 * draft 생성 API도 이 판정을 강제하지 않는다(클라 UX 힌트 전용). DB·외부 호출이 없다.
 */
@Service
public class TimelineDraftCheckService {

    /** 위치만으로 통과하려면 이 시간을 넘어야 한다(같으면 불통과). */
    private static final Duration LOCATION_ALONE = Duration.ofMinutes(30);
    /** 알림만으로 통과하는 최소 건수. */
    private static final int NOTIFICATIONS_ALONE = 3;
    /** 알림이 1건 이상 있을 때 통과에 필요한 최소 위치 시간. */
    private static final Duration LOCATION_WITH_NOTIFICATION = Duration.ofMinutes(20);

    /**
     * 컨트롤러가 넘긴 {@code applicationVersion}은 버전별 분기가 없어 받기만 한다(컨벤션 일관성).
     */
    public CheckDraftResponse check(String applicationVersion, List<CheckDraftSourceItem> sourceItems) {
        requireValid(sourceItems);

        boolean hasPhotoOrCalendar = false;
        int notifications = 0;
        Duration location = Duration.ZERO;
        for (CheckDraftSourceItem item : sourceItems) {
            switch (item.itemType()) {
                case PHOTO, CALENDAR -> hasPhotoOrCalendar = true;
                case STAY, MOVEMENT -> location = location.plus(locationDuration(item.startAt(), item.endAt()));
                case NOTIFICATION -> notifications++;
                case HEALTH -> {
                    // #594 정책 결정으로 판정에 쓰지 않는다 — 단독으로 통과시키지도, 다른 조건을 막지도 않는다.
                }
            }
        }

        boolean creatable = hasPhotoOrCalendar
                || location.compareTo(LOCATION_ALONE) > 0
                || notifications >= NOTIFICATIONS_ALONE
                || (location.compareTo(LOCATION_WITH_NOTIFICATION) >= 0 && notifications >= 1);
        return new CheckDraftResponse(creatable);
    }

    private static Duration locationDuration(LocalDateTime startAt, LocalDateTime endAt) {
        if (endAt == null || endAt.isBefore(startAt)) {
            return Duration.ZERO;
        }
        return Duration.between(startAt, endAt);
    }

    private static void requireValid(List<CheckDraftSourceItem> sourceItems) {
        if (sourceItems == null) {
            throw new IllegalArgumentException("sourceItems is required");
        }
        for (int i = 0; i < sourceItems.size(); i++) {
            CheckDraftSourceItem item = sourceItems.get(i);
            if (item == null) {
                throw new IllegalArgumentException("sourceItem is null: index=" + i);
            }
            if (item.itemType() == null) {
                throw new IllegalArgumentException("sourceItem has null itemType: index=" + i);
            }
            if (item.startAt() == null) {
                throw new IllegalArgumentException("sourceItem requires startAt: index=" + i);
            }
        }
    }
}
