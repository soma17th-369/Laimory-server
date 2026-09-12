# #474 고아 Item 스위퍼 전환

고아 스위퍼는 `MOD(timeline_item_id - 1, serverCount * workerCount)`가
`workerId * workerCount + localIndex`인 후보를 slot당 최대 batch-size개 한 번 처리한다.
기본은 서버 2대 × slot 1개 × 후보 250개이며 PK 공백과 쏠림을 수용한다. S3는 호출하지 않으므로
여러 slot도 같은 스케줄 스레드에서 순차 실행한다.

## 설정·전환

| 환경변수 | 서버 0 | 서버 1 |
|---|---|---|
| `TIMELINE_ORPHAN_SWEEP_WORKER_ID` | 0 | 1 |
| `TIMELINE_ORPHAN_SWEEP_SERVER_COUNT` | 2 | 2 |
| `TIMELINE_ORPHAN_SWEEP_WORKER_COUNT` | 1 | 1 |

worker-id는 중복 없이 0부터 server-count 미만이며 server-count·worker-count는 전체 서버에서 같다.
docker profile의 server-count 기본값은 1이고 dev DB를 공유하는 test 앱은 기존처럼 worker를 끈다.
`TIMELINE_ORPHAN_SWEEP_BATCH_SIZE` 기본값 250과 cron/zone은 유지한다. 기존
`TIMELINE_ORPHAN_SWEEP_MAX_BATCHES_PER_RUN`, `TIMELINE_ORPHAN_SWEEP_MAX_RUN_DURATION`은 제거한다.

AWS·host 변경은 대상·영향·복구 방법을 제시하고 별도 승인을 받는다. 이 변경에는 스키마 migration이 없다.
최초 전환·증설은 자동 배포 pause 및 진행 중 배포 확인 → 전체 참여 host의
`TIMELINE_ORPHAN_SWEEP_WORKER_ENABLED=false` 적용·진행 중 실행 종료 → 전체 코드·설정 적용·번호/health
확인 → worker 및 자동 배포 재개 순서다. 일부 적용이 실패하면 중지 상태를 유지한다.
`.env`는 컨테이너 재생성으로 반영하며 파일 수정이나 `docker restart`만으로 반영됐다고 간주하지 않는다.

원복도 전체 worker 중지 상태에서 구 image·구 설정을 전체 적용한 뒤 재개한다. 남은 관측 표시·시각은
구 스위퍼의 후보 조건이 아니므로 보존한다. 다른 PR의 migration이 적용됐다면 해당 migration의
구 image 호환성 복구 절차도 먼저 확인한다.
서버 장애 시 다른 서버가 담당을 인수하거나 번호를 바꾸지 않는다. 기존 `laimory_target_down`
경보로 같은 번호를 복구하고 다음 정규 실행에서 재조회하며 누락 실행 자동 보충은 없다.

## 최초 관측과 72시간 경보

선택한 최대 250개 PK 안에서 아직 관측되지 않았고 junction·사진 job이 모두 없는 Item에만
`modified_by='ORPHAN_SWEEPER'`와 앱 Clock의 KST 최초 관측 시각을 기록해 먼저 commit한다.
같은 PK는 새 처리 transaction에서 재검증한다. 처리 rollback·재조회가 최초 시각을 덮어쓰지 않는다.

기존 Item 재연결은 같은 transaction에서 junction 저장 전에 관측 표시를 해제한다.
Item UPDATE를 먼저 수행하여 junction FK 공유 잠금의 S→X 승격 교착을 피한다. 공통 감사 동작은 유지한다.

각 slot의 처리 commit/rollback 뒤 담당 전체에서 관측 후 72시간 이상이며 junction·job이 모두 없는
Item 수가 양수이면 workerIndex·count만 ERROR로 남긴다. 기존 application ERROR 경보를 사용한다.
이번 후보 밖의 관측 Item도 포함하며 job으로 넘긴 Item은 제외한다. 이후 사진 job 실패는 기존 만료
경보가 담당한다. 실제 고아 전환 시각이나 LIMIT·워커 중단 때문에 미관측인 적체의 대기시간은 측정하지 않는다.
