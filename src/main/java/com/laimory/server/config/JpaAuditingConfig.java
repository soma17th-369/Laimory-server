package com.laimory.server.config;

import com.laimory.server.common.ModifiedByType;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA 감사 활성화. AuditorAware는 사용자 도입 전이라 항상 OPERATION. */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<ModifiedByType> auditorAware() {
        return () -> Optional.of(ModifiedByType.OPERATION);
    }
}
