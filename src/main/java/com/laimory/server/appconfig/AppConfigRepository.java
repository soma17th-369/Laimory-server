package com.laimory.server.appconfig;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppConfigRepository extends JpaRepository<AppConfig, Long> {
    List<AppConfig> findTop2ByOrderByAppConfigIdAsc();
}
