# #474 사진 삭제 워커 전환

사진 삭제는 `MOD(timeline_photo_delete_job_id - 1, serverCount * workerCount)`가
`workerId * workerCount + localIndex`인 job을 slot당 최대 batch-size개 한 번 처리한다.
기본은 서버 2대 × slot 1개 × 후보 250개로 합계 후보 상한은 500개다. PK 공백과 쏠림을 수용한다.

## 설정

| 환경변수 | 서버 0 | 서버 1 |
|---|---|---|
| `TIMELINE_PHOTO_DELETE_WORKER_ID` | 0 | 1 |
| `TIMELINE_PHOTO_DELETE_SERVER_COUNT` | 2 | 2 |
| `TIMELINE_PHOTO_DELETE_WORKER_COUNT` | 1 | 1 |

worker-id는 중복 없이 0부터 server-count 미만이며 server-count·worker-count는 전체 서버에서 같다.
worker-count가 executor 크기와 실제 slot 수를 함께 정한다. docker profile의 server-count 기본값은 1이고
dev DB를 공유하는 test 앱은 기존처럼 worker를 비활성화하고 참여 서버에서 제외한다.
`TIMELINE_PHOTO_DELETE_BATCH_SIZE` 기본값 250과 cron/zone은 유지한다. 기존
`TIMELINE_PHOTO_DELETE_CONCURRENCY`, `TIMELINE_PHOTO_DELETE_MAX_BATCHES_PER_RUN`,
`TIMELINE_PHOTO_DELETE_MAX_RUN_DURATION`은 제거한다.

## 전환·원복·증설

AWS·host 변경은 대상·영향·복구 방법을 제시하고 별도 승인을 받는다. 이 변경에는 스키마 migration이 없다.

1. 자동 배포를 pause하고 진행 중 배포를 확인한다. 전체 참여 host에서
   `TIMELINE_PHOTO_DELETE_WORKER_ENABLED=false`로 컨테이너를 재생성하고 진행 중 실행을 종료한다.
2. 모든 서버에 새 코드와 담당 설정을 적용한다. 번호 중복·누락, 공통 설정과 health를 확인한다.
   일부 적용이 실패하면 worker를 중지한 채 복구한다.
3. 전체 적용 뒤 worker를 재개하고 다음 정규 실행과 health를 확인한 뒤 자동 배포를 재개한다.
   `.env` 수정이나 `docker restart`만으로 환경변수가 반영됐다고 간주하지 않는다.

원복은 전체 worker를 중지한 상태에서 구 image와 구 설정으로 전체 복귀한 뒤 재개한다.
증설도 전체 중지·설정 적용·번호 확인·재개를 따른다. 구·신 분배 또는 서로 다른 전체 워커 수가
혼재하는 동안 worker를 실행하지 않는다. 다른 PR의 migration이 이미 적용됐다면 해당 migration의
구 image 호환성 복구 절차도 먼저 확인한다.

## 보존되는 처리 계약

생성일 D의 D+1~D+3 처리 창, PROCESSING·updated_at 전이, 갱신 건수 부족 시 전체 rollback,
재연결 API의 object_key 잠금·409, S3 직전 재검증, 성공 시 job→Item 삭제와 실패 보존은 유지한다.
후보 상한 초과·실패는 다음 정규 실행에서 재조회하되 처리 창이 끝나면 자동 재시도하지 않는다.
만료 job은 기존 ERROR 경보로 확인한다. 서버 장애 시 담당 자동 인수·누락 실행 보충은 없으며,
기존 `laimory_target_down` 경보를 받고 같은 번호로 복구한다.
