package com.laimory.server.push.service;

import java.util.UUID;

/**
 * 발송 대상 조회 결과 한 행 — subject와 그 설치의 FID pair다. worker가 subject별 동의 상태를 target별
 * boolean으로 투영할 때 쓰는 최소 단위이며 엔티티 전체를 적재하지 않는다.
 */
public record SubjectInstallation(UUID subjectId, String firebaseInstallationId) {
}
