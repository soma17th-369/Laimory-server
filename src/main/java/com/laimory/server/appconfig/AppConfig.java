package com.laimory.server.appconfig;

import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "app_config")
@Getter
public class AppConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long minAppVersion;

    private Long recommendAppVersion;

    private String debugTestMessage;
}
