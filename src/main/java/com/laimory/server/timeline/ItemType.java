package com.laimory.server.timeline;

/**
 * 타임라인 아이템 종류. v1은 PHOTO/CALENDAR/LOCATION/MOVEMENT.
 * (향후 PAYMENT/CALL/MESSAGE/APP_USAGE/HEALTH/MUSIC 등 확장 가능)
 */
public enum ItemType {
    PHOTO,
    CALENDAR,
    LOCATION,
    MOVEMENT
}
