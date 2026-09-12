package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TimelineOrphanItemSweeperPropertiesTest {
    @Test
    void assignsEverySlotExactlyOnceForTwoServers() {
        for (int count : new int[] {1, 2}) {
            Set<Integer> assigned = new HashSet<>();
            for (int server = 0; server < 2; server++) {
                var properties = new TimelineOrphanItemSweeperProperties(true, 250, server, 2, count);
                assertThat(properties.getWorkerCount()).isEqualTo(count);
                assertThat(properties.getBatchSize()).isEqualTo(250);
                assertThat(properties.getTotalWorkerCount()).isEqualTo(2 * count);
                for (int slot = 0; slot < count; slot++) {
                    assertThat(assigned.add(properties.getWorkerIndex(slot))).isTrue();
                }
                assertThatIllegalArgumentException().isThrownBy(() -> properties.getWorkerIndex(count));
                assertThatIllegalArgumentException().isThrownBy(() -> properties.getWorkerIndex(-1));
            }
            assertThat(assigned).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.IntStream.range(0, 2 * count).boxed().toList());
        }
    }

    @Test
    void rejectsInvalidWorkerTopology() {
        assertThatIllegalStateException().isThrownBy(() -> new TimelineOrphanItemSweeperProperties(true, 250, -1, 2, 1));
        assertThatIllegalStateException().isThrownBy(() -> new TimelineOrphanItemSweeperProperties(true, 250, 2, 2, 1));
        assertThatIllegalStateException().isThrownBy(() -> new TimelineOrphanItemSweeperProperties(true, 250, 0, 0, 1));
        assertThatIllegalStateException().isThrownBy(() -> new TimelineOrphanItemSweeperProperties(true, 250, 0, 2, 0));
        assertThatIllegalStateException().isThrownBy(() -> new TimelineOrphanItemSweeperProperties(true, 1001, 0, 2, 1));
    }
}
