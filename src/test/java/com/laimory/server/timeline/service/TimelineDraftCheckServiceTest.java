package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.CheckDraftSourceItem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 타임라인 생성 가능 확인(#594) 판정 경계 — 통과 조건 넷(PHOTO·CALENDAR / 위치 30분 초과 / 알림 3건 /
 * 위치 20분+알림 1건) 중 하나라도 맞으면 creatable이다.
 */
class TimelineDraftCheckServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 16, 9, 0);

    private final TimelineDraftCheckService service = new TimelineDraftCheckService();

    private static CheckDraftSourceItem item(ItemType type) {
        return new CheckDraftSourceItem(type, BASE, null);
    }

    private static CheckDraftSourceItem location(ItemType type, Duration duration) {
        return new CheckDraftSourceItem(type, BASE, BASE.plus(duration));
    }

    private static List<CheckDraftSourceItem> notifications(int count) {
        return Collections.nCopies(count, item(ItemType.NOTIFICATION));
    }

    private static List<CheckDraftSourceItem> concat(List<CheckDraftSourceItem> first, List<CheckDraftSourceItem> second) {
        List<CheckDraftSourceItem> items = new ArrayList<>(first);
        items.addAll(second);
        return items;
    }

    private boolean creatable(List<CheckDraftSourceItem> items) {
        return service.check("v1", items).creatable();
    }

    @Test
    void singlePhotoIsCreatable() {
        assertThat(creatable(List.of(item(ItemType.PHOTO)))).isTrue();
    }

    @Test
    void singleCalendarIsCreatable() {
        assertThat(creatable(List.of(item(ItemType.CALENDAR)))).isTrue();
    }

    @Test
    void locationOfExactlyThirtyMinutesAloneIsNotCreatable() {
        assertThat(creatable(List.of(location(ItemType.STAY, Duration.ofMinutes(30))))).isFalse();
    }

    @Test
    void locationOverThirtyMinutesAloneIsCreatable() {
        assertThat(creatable(List.of(location(ItemType.STAY, Duration.ofMinutes(30).plusSeconds(1))))).isTrue();
    }

    @Test
    void stayAndMovementDurationsAreSummed() {
        List<CheckDraftSourceItem> items = List.of(
                location(ItemType.STAY, Duration.ofMinutes(20)),
                location(ItemType.MOVEMENT, Duration.ofMinutes(11)));

        assertThat(creatable(items)).isTrue();
    }

    @Test
    void twoNotificationsAloneAreNotCreatable() {
        assertThat(creatable(notifications(2))).isFalse();
    }

    @Test
    void threeNotificationsAloneAreCreatable() {
        assertThat(creatable(notifications(3))).isTrue();
    }

    @Test
    void twentyMinutesOfLocationWithOneNotificationIsCreatable() {
        List<CheckDraftSourceItem> items = concat(
                List.of(location(ItemType.STAY, Duration.ofMinutes(20))), notifications(1));

        assertThat(creatable(items)).isTrue();
    }

    @Test
    void locationJustUnderTwentyMinutesWithOneNotificationIsNotCreatable() {
        List<CheckDraftSourceItem> items = concat(
                List.of(location(ItemType.STAY, Duration.ofMinutes(20).minusSeconds(1))), notifications(1));

        assertThat(creatable(items)).isFalse();
    }

    @Test
    void twentyMinutesOfLocationWithoutNotificationIsNotCreatable() {
        assertThat(creatable(List.of(location(ItemType.STAY, Duration.ofMinutes(20))))).isFalse();
    }

    @Test
    void locationWithoutEndAtCountsAsZeroMinutes() {
        List<CheckDraftSourceItem> items = concat(List.of(item(ItemType.STAY)), notifications(1));

        assertThat(creatable(items)).isFalse();
    }

    @Test
    void locationWithEndAtBeforeStartAtCountsAsZeroMinutes() {
        CheckDraftSourceItem reversed = new CheckDraftSourceItem(ItemType.STAY, BASE, BASE.minusHours(2));

        assertThat(creatable(concat(List.of(reversed), notifications(1)))).isFalse();
    }

    @Test
    void healthAloneIsNotCreatable() {
        assertThat(creatable(List.of(item(ItemType.HEALTH), item(ItemType.HEALTH)))).isFalse();
    }

    @Test
    void emptySourceItemsAreNotCreatable() {
        assertThat(creatable(List.of())).isFalse();
    }

    @Test
    void nullSourceItemsAreRejected() {
        assertThatThrownBy(() -> service.check("v1", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullSourceItemElementIsRejected() {
        List<CheckDraftSourceItem> items = new ArrayList<>();
        items.add(null);

        assertThatThrownBy(() -> service.check("v1", items)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceItemWithoutItemTypeIsRejected() {
        List<CheckDraftSourceItem> items = List.of(new CheckDraftSourceItem(null, BASE, null));

        assertThatThrownBy(() -> service.check("v1", items)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceItemWithoutStartAtIsRejected() {
        List<CheckDraftSourceItem> items = List.of(new CheckDraftSourceItem(ItemType.PHOTO, null, null));

        assertThatThrownBy(() -> service.check("v1", items)).isInstanceOf(IllegalArgumentException.class);
    }
}
