package com.laimory.server.timeline.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * junction 복합 키의 값 기반 equals/hashCode 계약 검증. Hibernate가 이 객체를 영속성 컨텍스트
 * identity map의 키로 쓰므로, 같은 pair는 값으로 같아야 1차 캐시·중복 관리 판정이 동작한다.
 */
class TimelineEventItemIdTest {

    @Test
    void equals_samePair_isEqualWithSameHash() {
        TimelineEventItemId left = new TimelineEventItemId(11L, 21L);
        TimelineEventItemId right = new TimelineEventItemId(11L, 21L);

        assertThat(left).isEqualTo(left);
        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    @Test
    void equals_differentComponent_isNotEqual() {
        TimelineEventItemId base = new TimelineEventItemId(11L, 21L);

        assertThat(base).isNotEqualTo(new TimelineEventItemId(12L, 21L));
        assertThat(base).isNotEqualTo(new TimelineEventItemId(11L, 22L));
    }

    @Test
    void equals_nullAndOtherType_isNotEqual() {
        TimelineEventItemId base = new TimelineEventItemId(11L, 21L);

        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("11:21");
    }
}
