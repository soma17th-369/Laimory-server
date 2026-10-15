-- #514: 만료 refresh token 일일 정리(DELETE ... WHERE expires_at < ?)가 평시 하루 몫의 만료 범위만
-- range 스캔하도록 인덱스를 둔다. 만료 비율이 높아(첫 실행·정리 중단 후) 옵티마이저가 전체 스캔을 골라도
-- READ COMMITTED(#576)에서는 만료 행만 잠긴다.
-- additive index라 구 앱과 호환된다(rolling 배포 중 실행 가능). InnoDB online DDL(INPLACE, LOCK=NONE).
ALTER TABLE refresh_tokens ADD INDEX idx_refresh_tokens_expires_at (expires_at), ALGORITHM=INPLACE, LOCK=NONE;
