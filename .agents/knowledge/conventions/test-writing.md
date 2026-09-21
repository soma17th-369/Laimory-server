# 테스트 작성 규율

## Scope

무엇을 테스트하고, 어떤 품질로 작성하며, 실패한 테스트 앞에서 어떻게 판정하는지를 정한다.
실행 방법(Gradle task·tag·필요 인프라·CI 범위)은 [testing](../codebase/operations/testing.md)이 소유한다.

## Read When

테스트를 새로 쓰거나, 기존 테스트를 수정·삭제·비활성화하려 하거나, 구현 변경 후 테스트가 실패할 때 읽는다.

## Authoritative Sources

- `src/test/java/**` — 현재 테스트 자산과 패턴
- [domain invariants](../domain/invariants.md) — 테스트가 지키는 도메인 규칙
- `AGENTS.md` — 위험 비례 검증, 엣지케이스 승인 게이트

## 최상위 원칙: 테스트는 계약이다

**테스트를 성공시키기 위해 테스트를 수정하지 않는다.** 구현을 바꾼 뒤 테스트가 실패하면
기본 가정은 "구현이 틀렸다"이고, 첫 행동은 구현을 고치는 것이다. 행동을 검증하는 테스트를
깨뜨린 변경은 실제 클라이언트도 깨뜨린다. 이상적인 테스트는 한 번 쓰면 요구사항이 바뀌기
전까지 바뀌지 않는다.

테스트 수정·삭제·비활성화가 허용되는 경우는 넷뿐이며 각각 입증 요건이 있다.

| 경우 | 입증 요건 |
|---|---|
| 이번 작업의 요구사항이 계약 자체를 바꿨다 | PR 본문에 어떤 계약이 왜 바뀌는지 명시(이슈·결정 링크) |
| 테스트가 애초에 잘못된 동작을 검증하고 있었다 | 올바른 계약의 근거 제시 + 사용자 승인 |
| 플레이키(비결정적 실패)다 | 실패 원인 재현·설명 + 근본 해결 + 사용자 승인. 타임아웃 연장·재시도 추가 같은 완화는 해결이 아니다 |
| 동작 불변 리팩터링이 테스트가 결합한 내부 구조를 바꿨다 | PR 본문에 외부 동작 불변 근거(관련 행동 테스트 green) 명시 + 동등 이상의 행동 검증 유지. assertion 약화·시나리오 삭제는 여기서도 금지다 |

다음은 전부 "테스트를 통과시키기 위한 수정"의 변형이며, 위 요건 없이는 금지다.

- assertion 약화: 구체 값 검증을 `isNotNull()` 류로 바꾸기, assertion 삭제
- `@Disabled`·주석 처리·tag 제외로 테스트를 실행에서 빼기
- 실패하는 케이스를 비껴가도록 테스트 입력을 바꾸기
- 테스트를 통과시키기 위한 프로덕션 특수 분기(테스트 전용 플래그·조건문)

CI 녹색은 완료의 전제조건이지 완료의 증거가 아니다. "이 실패는 무시해도 된다"가 한 번
허용된 테스트는 이미 죽은 것이고, 그 습관은 건강한 테스트의 실패까지 무시하게 만든다.

## 무엇을 검증하는가: 구현이 아니라 행동

- 관측 가능한 결과를 검증한다: 응답 본문·에러 코드, DB에 남는 상태, enqueue된 작업.
  구현만 바뀌고 행동이 그대로면 기존 테스트는 하나도 바뀌지 않아야 한다 — 이것이 위 원칙이
  지켜질 수 있는 전제다.
- 프로덕션 코드의 내부(호출 순서, 중간 계산값, 코드를 옮겨 적은 기대값)를 그대로 비추는
  테스트는 모든 변경에 깨지면서 결함은 못 잡는다. 발견하면 행동 기준으로 재작성하거나
  삭제한다(삭제는 위 판정 절차).
- 상호작용 검증(`verify(...)`)보다 상태 검증을 우선한다. `verify`는 그 호출 자체가 계약일 때만
  쓴다(예: AI dispatch 발송, push 전송, 삭제 job enqueue).
- mock은 실제 협력자를 쓸 수 없을 때만 쓴다: 외부 네트워크, 호출당 비용, 에러 조건 유발.
  같은 프로세스의 값 객체·순수 로직은 실물을 쓴다.
- private 메서드는 직접 테스트하지 않고 public API 경유로 검증한다. private을 테스트하고
  싶다면 대개 책임 분리가 필요하다는 신호다.

## 무엇을 테스트하지 않는가

- 위험에 비례해서만 쓴다(`AGENTS.md`). 드문 조건(경합·ms 타이밍·장애 중첩) 전용 테스트는
  엣지케이스 승인 게이트를 거친다.
- 두지 않기로 한 방어를 위한 테스트를 만들지 않는다.
  [invariants의 "수동 입력 방어의 경계"](../domain/invariants.md)가 제거한 방어의 부정 테스트를
  추가하는 것은 그 방어를 되살리는 것과 같다.
- 정적으로 정해진 상수 값의 검증, 이번 변경에서 제거된 로직의 부정 테스트는 추가하지 않는다.
- coverage 비율은 merge gate가 아니다([testing](../codebase/operations/testing.md)). 낮은
  커버리지는 부족의 증거지만 높은 커버리지는 품질의 증거가 아니고, 숫자를 채우기 위한
  assertion 없는 테스트는 음의 가치다.

## 버그 수정은 red → green

버그를 고칠 때는 먼저 그 버그를 재현하는 실패 테스트를 쓰고, 그다음 구현을 고쳐 통과시킨다.
재현 테스트가 수정과 같은 PR에 들어가야 "고쳐졌고, 다시 깨지면 잡힌다"가 보장된다.

## 테스트 레이어 선택

행동을 검증할 수 있는 가장 낮은 레이어를 고른다. 판별 기준은 속도와 결정성이다.

| 레이어 | 선택 기준 |
|---|---|
| 단위(JUnit + Mockito, 컨텍스트 없음) | 순수 로직·정책·변환. 기본값 |
| slice(`@WebMvcTest` 등) | HTTP 계약(직렬화·검증·에러 envelope·인증 배선)만 볼 때 |
| `@SpringBootTest`(인프라 없음) | 여러 빈의 배선·설정 자체가 검증 대상일 때(예: `AdminHttpTest`) |
| integration(`@Tag("integration")` + `@ActiveProfiles("docker")`) | 실 MySQL·Redis 의미가 검증 대상일 때: 제약·잠금·트랜잭션 경계·시간 프레임·Redis 계약 |

- 상위 레이어가 잡은 결함에 대응하는 하위 테스트가 없으면 하위에 쓴다. 하위에서 이미 검증된
  행동을 상위에서 중복 검증하지 않는다.
- Spring 컨텍스트 캐시를 지킨다: 같은 설정을 공유하는 테스트는 컨텍스트를 한 번만 띄운다.
  `@MockitoBean` 추가·프로퍼티 변형은 새 컨텍스트를 만들므로 필요한 곳에만 쓴다.

## 결정성

- 비동기 결과를 고정 `Thread.sleep`으로 기다리지 않는다 — Awaitility로 조건을 폴링한다.
  폴링이 검증 의미를 바꾸는 시간 경과 대기는 예외다(예: `KakaoGeoResourceBoundaryTest`의
  half-open 전이는 wait 경과 뒤 첫 호출 자체가 probe라 폴링하면 호출을 소비한다). 예외에는
  폴링할 수 없는 이유를 주석으로 남긴다.
- 시간 의존 로직은 프로덕션의 `Clock` 주입을 그대로 써서 테스트에 고정 Clock을 넣는다.
  테스트에서 벽시계 `now()`를 직접 비교하지 않는다.
- 실제 외부 네트워크 금지. 외부 HTTP(Kakao 등)는 loopback `MockWebServer`로 production 배선
  그대로 검증한다. **AI dispatch 실발송은 테스트·스크립트에서 금지**(호출당 비용) — 명시적
  사용자 승인이 있을 때만 예외다.
- 공유 로컬 DB 격리: 각 테스트가 자기 데이터를 스스로 만들고(`AtomicLong` seed로 고유 ID 대역
  확보), 실행 순서·다른 테스트가 남긴 상태에 의존하지 않는다. 모든 테스트는 단독 실행으로도
  통과해야 한다.
- 정리 기본값은 `@AfterEach` 명시 삭제다. 클래스 `@Transactional` 롤백 격리는 commit 시점
  동작(제약 위반·flush·auditing)이나 별도 스레드·worker가 읽는 데이터가 검증에 개입하지 않는
  테스트에서만 쓴다 — 롤백은 그런 테스트에서 거짓 통과를 만든다.

## 구조·네이밍

- 새 테스트 메서드는 영어 camelCase로 쓰고, 시나리오와 기대 결과가 이름에 드러나게 한다:
  `failedCallbackClosesTaskWithoutChangingDocument`. 기존 한국어 서술형 이름은 일괄 rename하지
  않고, 해당 파일을 크게 고칠 때 함께 정리할 수 있다.
- 결과가 숨는 이름을 쓰지 않는다: "no-op"이 아니라 "skipsDuplicateWithoutError" 처럼 실제
  일어나는 일을 적는다.
- Arrange–Act–Assert 3단 구조로 쓰고, 테스트 하나가 검증하는 행동은 하나로 한다.
- 테스트 본문에 조건문·반복문 같은 로직을 넣지 않는다. 로직이 필요해 보이면 테스트를 쪼갠다.
- 중복 제거보다 자기완결이 우선이다: 본문만 읽고 시나리오를 이해할 수 있어야 한다.
  헬퍼·fixture는 준비(생성)까지만 맡기고, assertion을 헬퍼 뒤로 숨기지 않는다.
- 공용 준비 코드는 `src/test/java/com/laimory/server/testsupport/`를 먼저 찾아 쓰고, 두 곳
  이상에서 같은 준비가 반복될 때만 새로 승격한다.

## 전역 계약 테스트 (`arch/`)

`src/test/java/com/laimory/server/arch/`의 세 테스트는 저장소 전역 계약을 고정한다. 실패하면
"어떻게 통과시키나"가 아니라 "이 계약을 바꿀 근거가 있나"부터 판단한다. 예외 추가는 테스트에
선언하고 PR에 근거를 적는다.

- `ApiAuthenticationContractTest` — `EXPECTED_PRINCIPALS`에 등록된 보호 API의 인증·응답 계약
  선언. 새 보호 API는 이 map에 등록해야 검사 대상이 된다 — 누락돼도 테스트는 통과한다
- `RedisAccessArchTest` — Redis 접근 경계
- `SubjectMappingAccessArchTest` — subject 매핑 접근 경계

## Invariants

- 구현 변경과 테스트 수정·삭제·비활성화가 같은 PR에 있으면, 수정된 테스트마다 허용 4경우 중
  어디에 해당하는지와 근거를 PR 본문에 적는다.
- 실패한 테스트는 원인 분류(구현 결함 / 계약 변경 / 테스트 결함 / 플레이키) 없이 넘어가지 않는다.
- 검증 결과 판정은 [testing](../codebase/operations/testing.md)의 exit code 규칙을 따른다
  (`./gradlew test | tail`은 실패해도 exit 0).

## External References

아래 원문을 검증해 이 저장소 문맥으로 흡수했다. 갱신 시에도 원문을 재확인한다.

- Software Engineering at Google ch.11–12 (abseil.io/resources/swe-book) — unchanging tests, test sizes, DAMP, coverage
- Google Testing Blog — Test Behavior Not Implementation · Change-Detector Tests Considered Harmful · Don't Overuse Mocks
- Martin Fowler — Practical Test Pyramid · Eradicating Non-Determinism in Tests · Self-Testing Code
- Vladimir Khorikov, Unit Testing Principles, Practices, and Patterns — 좋은 테스트 4기둥, 커버리지의 위상
- goldbergyoni/javascript-testing-best-practices — AAA·네이밍·선언적 assertion

## Update When

판정 절차(허용 4경우), 레이어 선택 기준, 결정성·격리·네이밍 규칙이 바뀔 때 갱신한다.
실행 방법 변화는 이 문서가 아니라 [testing](../codebase/operations/testing.md)을 갱신한다.

## Validation

```bash
ls src/test/java/com/laimory/server/arch/ src/test/java/com/laimory/server/testsupport/
./gradlew test > /tmp/test.log 2>&1; echo $?
```
