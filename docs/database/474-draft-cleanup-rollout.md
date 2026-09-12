# #474 초안 원본 정리 전환

초안 정리는 `MOD(timeline_draft_source_item_id - 1, serverCount * workerCount)`가
`workerId * workerCount + localIndex`인 만료 행을 slot당 최대 batch-size개 한 번 처리한다.
기본은 서버 2대 × slot 1개 × 후보 250개이며 PK 공백과 쏠림을 수용한다.
7일 보관기간, S3 성공 후 행 삭제와 실패 행 보존은 유지한다. 실패 행은 다음 정규 실행에서
재조회하며 같은 날 재선택을 막는 선점 시각과 초안 적체 전용 알림은 없다.

## 설정

| 환경변수 | 서버 0 | 서버 1 |
|---|---|---|
| `DRAFT_CLEANUP_WORKER_ID` | 0 | 1 |
| `DRAFT_CLEANUP_SERVER_COUNT` | 2 | 2 |
| `DRAFT_CLEANUP_WORKER_COUNT` | 1 | 1 |

worker-id는 중복 없이 0부터 server-count 미만이며, server-count·worker-count는 모든 참여 서버에서
같아야 한다. worker-count가 executor 크기와 실제 slot 수를 함께 정한다. docker profile의
server-count 기본값은 1이고, dev DB를 공유하는 test 앱은 worker를 끈 채 참여 서버에서 제외한다.
`DRAFT_CLEANUP_BATCH_SIZE`의 기본값 250과 cron/zone은 유지한다. 기존 `DRAFT_CLEANUP_CONCURRENCY`,
`DRAFT_CLEANUP_MAX_BATCHES_PER_RUN`, `DRAFT_CLEANUP_MAX_RUN_DURATION`은 제거한다.

## 최초 전환: 코드 먼저, V2는 나중

이 문서는 실행 승인이 아니다. AWS·host·DB 변경은 대상·영향·복구 방법을 제시하고 별도 승인을 받는다.
**일반 rolling 배포로 V2를 바로 적용하면 안 된다.** 구버전은 worker가 꺼져 있어도 JPA 조회에서
`cleanup_available_at`을 참조한다. 같은 DB를 사용하는 모든 앱의 구 매핑을 먼저 제거해야 한다.

1. 병합 전에 `DEPLOY_PAUSED=true`로 자동 배포를 중지하고 진행 중인 배포가 없는지 확인한다.
   각 DB의 Flyway 이력과 스키마를 조회한다. 시작 상태는 V1이며 다른 migration이 추가됐다면
   target을 임의로 적용하지 않고 실제 배포 SQL 집합을 다시 확인한다.
2. 모든 참여 host에서 `DRAFT_CLEANUP_WORKER_ENABLED=false`로 컨테이너를 재생성하고
   진행 중 실행이 끝났는지 확인한다.
3. 모든 새 앱에 임시 `SPRING_FLYWAY_TARGET=1`과 담당 설정을 적용해 새 코드를 먼저 배포한다.
   Flyway 자체는 켜 둔다. dev DB를 공유하는 test 앱도 교체하거나 중지해 구 매핑이 남지 않게 한다.
   일부 교체가 실패하면 worker를 중지한 채 복구한다.
4. 구 매핑·쿼리가 모두 사라지면 임시 target을 제거하고 새 앱 컨테이너를 재생성한다.
   V2 성공 이력, `cleanup_available_at`·`idx_draft_source_cleanup` 제거,
   `idx_draft_source_created` 유지 및 앱 health를 확인한다. V1과 동결 fixture는 수정하지 않는다.
5. 전체 번호·공통 설정 일치를 확인하고 worker를 재개한다. 다음 일일 실행과 health를 확인한 뒤
   자동 배포를 재개한다. `.env` 수정이나 `docker restart`만으로 설정이 반영됐다고 간주하지 않는다.

사진 삭제·고아 스위퍼를 별도 PR로 전환할 때는 각각의 worker 중지·전체 적용 절차를 따른다.
V2는 초안 선점 컬럼·인덱스만 제거하며 데이터 행을 삭제하지 않는다.

## 복구·증설·장애

V2 적용 전에는 worker 중지 상태에서 구 image로 복귀한다. V2 적용 뒤 구 image로 돌아갈 때는
관련 배포·worker를 중지하고 실제 스키마를 확인한 뒤 아래 호환성 복구 DDL을 별도 승인하여 먼저
적용한다. 그다음 구 image와 구 설정을 전체 적용하고 재개한다.

```sql
ALTER TABLE timeline_draft_source_items
    ADD COLUMN cleanup_available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX idx_draft_source_cleanup (cleanup_available_at, created_at, timeline_draft_source_item_id);
```

제거된 과거 선점 시각은 복구되지 않는다. Flyway 이력을 삭제하거나 V1/V2를 수정하지 않으며,
재전환 시 여분 컬럼 정리는 후속 migration으로 기록한다.

증설·번호 원복도 전체 worker 중지·실행 종료 → 전체 설정 적용·번호 검증 → 재개 순서다.
서버 장애 시 다른 서버가 담당을 인수하거나 server-count를 줄이지 않는다. 기존 `laimory_target_down`
경보로 운영자가 같은 번호를 복구하며 다음 정규 실행에서 재조회한다. 누락 실행 자동 보충은 없다.
