package com.laimory.server.timeline;

/**
 * 타임라인 아이템 종류. PHOTO/CALENDAR/LOCATION/MOVEMENT/HEALTH/NOTIFICATION.
 * (향후 PAYMENT/CALL/MESSAGE/APP_USAGE/MUSIC 등 확장 가능)
 */
public enum ItemType {
    PHOTO,
    CALENDAR,
    LOCATION,
    MOVEMENT,
    HEALTH,
    NOTIFICATION
}
