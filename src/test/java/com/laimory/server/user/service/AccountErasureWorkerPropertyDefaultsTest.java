package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * checked-in 기본값이 실제로 적용되는지 확인한다.
 *
 * <p>{@code @Value}의 {@code :기본값}은 property가 <b>정의되지 않았을 때만</b> 쓰인다.
 * {@code application.properties}가 같은 키를 정의하고 있으면 그쪽이 이긴다 — Java 쪽 기본값만 고치고
 * properties를 두면 의도한 변경이 조용히 무력화된다. 이 테스트가 그 어긋남을 잡는다.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = AccountErasureWorkerProperties.class,
        properties = "photo.upload.presign-ttl=10m")
class AccountErasureWorkerPropertyDefaultsTest {

    @Autowired
    private AccountErasureWorkerProperties properties;

    @Test
    void run당_처리량은_batch_수가_정한다() {
        // claim 크기는 설정이 아니라 상수 1이므로 이 값이 곧 run당 최대 job 수다.
        assertThat(properties.getMaxBatchesPerRun()).isEqualTo(100);
    }

    @Test
    void 확정된_정책값이_적용된다() {
        assertThat(properties.getGracePeriodDays()).isEqualTo(7);
        assertThat(properties.getWindowDays()).isEqualTo(3);
    }
}
