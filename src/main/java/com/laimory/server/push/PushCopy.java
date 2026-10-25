package com.laimory.server.push;

/**
 * 사용자에게 보일 알림 문구 한 벌 — 제목과 본문은 항상 짝으로 고른다.
 *
 * @param title 알림 제목
 * @param body 알림 본문
 */
public record PushCopy(String title, String body) {
}
