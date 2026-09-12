# #474 정리 스케줄러 전환

초안 원본 정리·사진 삭제·고아 Item 정리는 각 테이블의 숫자 PK로 담당을 고정한다.
모든 참여 서버의 설정이 맞는 동안 `MOD(id - 1, serverCount * workerCount)`가
`workerId * workerCount + localIndex`인 후보만 처리한다. 서버별 번호는 0부터 시작하고
localIndex는 0 이상 workerCount 미만이다. 기본은 서버 2대 × 서버당 slot 1개이며,
각 slot은 일일 실행당 후보 최대 250개 한 배치를 처리한다. PK 공백과 처리량 쏠림을 수용한다.

## 설정

각 host의 `.env`에 아래 값을 명시한다. 같은 스케줄러의 server-count와 worker-count는
모든 참여 서버에서 같아야 하고 worker-id는 중복 없이 0부터 server-count 미만으로 배정한다.
worker-count가 executor 크기와 실제 slot 수의 유일한 설정이다. 고아 스위퍼는 외부 I/O가 없어
같은 스케줄 스레드에서 slot을 순차 실행한다.

| 환경변수 | 서버 0 | 서버 1 |
|---|---|---|
| `DRAFT_CLEANUP_WORKER_ID` | 0 | 1 |
| `DRAFT_CLEANUP_SERVER_COUNT` | 2 | 2 |
| `DRAFT_CLEANUP_WORKER_COUNT` | 1 | 1 |
| `TIMELINE_PHOTO_DELETE_WORKER_ID` | 0 | 1 |
| `TIMELINE_PHOTO_DELETE_SERVER_COUNT` | 2 | 2 |
| `TIMELINE_PHOTO_DELETE_WORKER_COUNT` | 1 | 1 |
| `TIMELINE_ORPHAN_SWEEP_WORKER_ID` | 0 | 1 |
| `TIMELINE_ORPHAN_SWEEP_SERVER_COUNT` | 2 | 2 |
| `TIMELINE_ORPHAN_SWEEP_WORKER_COUNT` | 1 | 1 |

`*_BATCH_SIZE`의 기본값 250과 cron/zone은 유지한다. 이 세 스케줄러의 기존 `*_CONCURRENCY`,
`*_MAX_BATCHES_PER_RUN`, `*_MAX_RUN_DURATION`은 제거됐으므로 host 설정에서도 정리한다.
일일 리마인더·계정 삭제의 같은 이름 설정은 계속 사용한다.
`docker` profile은 독립 로컬 실행을 위해 server-count 기본값만 1이다.
dev DB를 공유하는 test는 기존 스케줄러 비활성화 설정을 유지하고 참여 서버로 세지 않는다.

## 최초 전환: 코드 먼저, 컬럼 제거는 나중

이 문서는 실행 승인이 아니다. AWS·host·DB 변경은 대상·영향·복구 방법을 제시하고 별도 승인을 받는다.
기존 배포는 한 서버씩 교체하고 앱 시작 시 Flyway를 실행하므로 **V2를 곧바로 일반 rolling 배포하면 안 된다.**
구버전은 정리 스케줄러 외의 JPA 조회에서도 `cleanup_available_at`을 매핑한다.
worker만 꺼 둔 구버전 앱이 남아 있어도 컬럼 DROP은 안전하지 않다.

1. 머지 전에 `DEPLOY_PAUSED`로 자동 배포를 중지하고 이미 진행 중인 배포가 없는지 확인한다.
   dev/prod별 Flyway 이력과 현재 스키마를 조회한다. 이 절차의 시작 상태는 V1이며,
   다른 후속 migration이 추가됐다면 target을 임의로 적용하지 않고 배포 SQL 집합을 다시 확인한다.
2. 모든 참여 host에서 `DRAFT_CLEANUP_WORKER_ENABLED=false`,
   `TIMELINE_PHOTO_DELETE_WORKER_ENABLED=false`, `TIMELINE_ORPHAN_SWEEP_WORKER_ENABLED=false`를
   적용하고 컨테이너를 재생성한다. 새 실행과 진행 중 실행이 모두 종료됐는지 확인한다.
3. **코드 전환 단계**의 모든 새 앱 컨테이너에는 임시 `SPRING_FLYWAY_TARGET=1`을 설정한다.
   이는 Flyway 자체를 끄지 않고 V2만 연기한다. 위 고정 담당 설정과 새 코드를 전체 배포한다.
   새 앱은 V1의 여분 컬럼이 있어도 동작한다. dev DB를 공유하는 test 앱도 새 코드로 교체하거나
   구버전 참조가 남지 않도록 중지한다. 일부 서버 교체가 실패하면 worker를 중지한 채 복구한다.
4. 같은 DB를 사용하는 모든 앱에서 구 매핑·구 cleanup 쿼리가 사라진 것을 확인한다.
   **스키마 전환 단계**에서 임시 `SPRING_FLYWAY_TARGET`을 제거하고 새 앱 컨테이너를 재생성한다.
   Flyway V2가 `idx_draft_source_cleanup`과 `cleanup_available_at`을 제거하고 JPA validate한다.
   `idx_draft_source_created`와 junction 양방향 인덱스, job의 Item UNIQUE 인덱스는 유지한다.
   V2 성공 이력, 컬럼/인덱스 제거, 나머지 인덱스와 앱 health를 확인한다.
5. 모든 서버의 worker-id 중복/누락, server-count·worker-count 일치를 확인한 뒤
   위 세 worker-enabled를 복원하고 컨테이너를 재생성한다. 전체 재개와 다음 일일 실행 로그를 확인한 뒤
   자동 배포를 재개한다. `.env` 수정이나 `docker restart`만으로 설정이 반영됐다고 간주하지 않는다.

V1 migration과 동결된 pre-Flyway fixture는 수정하지 않는다. 신규 DB는 V1→V2로 최종 구조를 만든다.
V2는 하나의 ALTER이며 데이터 행을 삭제하지 않는다. MySQL DDL을 앱 rollback으로 되돌릴 수는 없다.

## 증설·원복·장애

워커 수 변경과 번호 원복도 전체 worker 중지·실행 종료 → 전체 설정 적용·번호 검증 → 재개 순서다.
서버당 2개로 늘리면 서버 0은 0·1, 서버 1은 2·3을 맡고 모든 slot은 MOD 4를 쓴다.
서버 장애 시 다른 서버가 담당을 인수하거나 server-count를 줄이지 않는다. 기존 `laimory_target_down`
알림을 받고 운영자가 같은 번호로 복구하며, 다음 정규 실행에서 재조회한다. 누락 실행 자동 보충은 없다.
사진 job은 D+1~D+3 창을 넘으면 증설·복구 후에도 자동 재시도하지 않으며 기존 만료 경보로 확인한다.
초안 실패 행도 남겨 다음 실행에서 재조회하지만, 전용 적체 알림은 추가하지 않는다.

코드 전환 단계(V2 적용 전)의 복구는 worker 중지 상태에서 구 image로 돌린다. V2 적용 후 구 image로
돌려야 한다면 모든 관련 worker와 배포를 중지하고, 실제 스키마를 확인한 뒤 아래 호환성 복구 DDL을
별도 승인하여 먼저 적용한다. 여분 컬럼은 새 앱과도 호환된다. 그다음 구 image를 전체 배포하고
구 설정으로 전체 재개한다. `flyway_schema_history`를 삭제하거나 V2 파일을 수정하지 않는다.
복구 후 새 버전으로 재전환할 때는 여분 컬럼 정리를 별도 후속 migration으로 기록한다.

```sql
ALTER TABLE timeline_draft_source_items
    ADD COLUMN cleanup_available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX idx_draft_source_cleanup (cleanup_available_at, created_at, timeline_draft_source_item_id);
```

제거된 과거 선점 시각은 복구되지 않는다. 컬럼 복구 시각 이후 기존 일일 실행부터 다시 선점한다.
데이터 복구가 필요한 별도 사고는 검증된 backup 절차를 사용한다.

## 고아 관측

선택한 최대 250개 PK 안에서 아직 관측되지 않았고 junction·사진 job이 모두 없는 Item에만
`modified_by='ORPHAN_SWEEPER'`, `updated_at=앱 Clock의 KST 최초 관측 시각`을 기록해 먼저 commit한다.
같은 PK를 새 처리 transaction에서 읽어 재검증하며 처리 실패가 최초 기록을 되돌리지 않는다.
기존 Item 재연결은 같은 연결 transaction에서 표시를 해제한다. 공통 AuditorAware는 바꾸지 않는다.

각 slot의 처리 commit/rollback 뒤, 자기 담당 전체에서 72시간 이상 관측된 고아 수가 양수이면
workerIndex·count만 ERROR로 남긴다. 기존 application ERROR 경보를 사용하고 새 채널은 없다.
job으로 넘긴 Item은 집계에서 제외하며 이후 실패는 기존 사진 job 만료 경보가 담당한다.
실제 고아 전환 시각, LIMIT 밖에서 아직 관측하지 못한 Item, 중단된 워커의 미관측 적체는 측정하지 않는다.
