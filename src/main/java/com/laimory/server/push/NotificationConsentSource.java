package com.laimory.server.push;

/** 동의·철회가 접수된 경로 — 증적에 보존한다. */
public enum NotificationConsentSource {

    /** 인증 사용자의 푸시 설정 화면. */
    PUSH_SETTINGS,

    /** 알림에서 바로 진입한 비로그인 installation 수신거부. */
    INSTALLATION_OPT_OUT
}
