package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class RefreshTokenSweeperTest {

    @Test
    void databaseFailureIsLoggedWithoutPropagatingToScheduler() {
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.deleteAllExpiredBefore(any())).thenThrow(new QueryTimeoutException("timeout"));
        RefreshTokenSweeper sweeper = new RefreshTokenSweeper(refreshTokenService,
                Clock.fixed(Instant.parse("2026-10-14T18:00:00Z"), ZoneId.of("Asia/Seoul")));

        assertThatCode(sweeper::sweepExpiredRefreshTokens).doesNotThrowAnyException();
    }
}
