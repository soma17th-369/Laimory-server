package com.laimory.server.appconfig;

import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "app_config")
@Getter
public class AppConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "app_config_id")
    private Long appConfigId;

    private Long minAppVersion;

    private Long recommendAppVersion;

    private String debugTestMessage;
}
