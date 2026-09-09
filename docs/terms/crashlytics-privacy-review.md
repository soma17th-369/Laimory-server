# Firebase Crashlytics 개인정보 처리방침 반영 근거

확인일: 2026-09-09. 대상은 한국 이용자에게 제공하는 Android 앱의 오류 진단과
개인정보 처리방침 `1.0`이다. 공개 원문은 [개인정보 처리방침](drafts/08-privacy-policy.md),
구현·게시 추적은 [#468](https://github.com/soma17th-369/Laimory-server/issues/468)에서 관리한다.
이 문서는 공개 자료와 코드에 근거한 적용 판단이며 개별 유권해석이나 전체 약관의 법률 검토 완료를 뜻하지 않는다.

## 1. 수집 내용과 처리 범위

Android `develop`의 [9bb48758](https://github.com/soma17th-369/Laimory-android/tree/9bb48758cbb986f0e48794884376fe8a72e84862)을
읽기 전용으로 대조했다. [PR #339](https://github.com/soma17th-369/Laimory-android/pull/339)의
Firebase BoM은 34.15.0, Crashlytics 런타임은 20.0.6, Gradle 플러그인은 3.0.8이다.
플러그인 버전과 런타임 버전을 구분한다.

| 항목 | 확인한 동작과 원문 반영 |
| --- | --- |
| SDK 기본 진단 | 오류 시각·유형·메시지·스택·스레드, 기기·운영체제·앱 정보와 상태를 제1조에 포함 |
| 식별자 | 설치 식별자, Firebase/Crashlytics 설치 ID, 세션 및 오류 보고서 식별자를 설치·세션·오류 보고서 식별자로 묶어 공개. SDK 보고서의 Firebase 설치 인증 토큰도 앱 설치 인증정보로 공개하며 앱 로그인 토큰과 구분. 이름·이메일·계정 ID로 사용자를 지정하는 `setUserId` 호출은 확인되지 않음 |
| 상태 키 | 현재 화면 경로와 로그인 여부. 화면 경로에 전달되는 상세 인자는 제외 |
| 앱 로그 | INFO 이상 진단 로그, 기능 단계·성공/실패·처리 건수, 설치·작업 ID의 마지막 6자리, 푸시 메시지 ID·처리 상태·데이터 항목명. 모든 식별자가 마스킹된다고 설명하지 않음 |
| 예외 메시지 | 앱이 수동 보고하는 예외는 `RedactedThrowable`로 메시지를 제거. SDK 자동 비정상 종료 보고는 별도 경로이므로 오류 메시지를 수집 항목에서 빼지 않음 |
| 수집 시점 | debug는 비활성화하고 qa/release는 SDK 기본 자동 수집을 사용. 가입 전 앱 실행부터 초기화될 수 있으며, 저장된 오류 보고서는 다음 실행에서 전송될 수 있음 |
| 목적 | 장애 위치·기기별 재현 조건·실행 단계·영향 범위를 확인하고 오류를 수정. 광고·성향 분석 목적은 부여하지 않음 |

앱 근거: [Logger](https://github.com/soma17th-369/Laimory-android/blob/9bb48758cbb986f0e48794884376fe8a72e84862/core/util/src/main/java/com/soma369/laimory/core/util/logging/Logger.kt#L131),
[화면 경로](https://github.com/soma17th-369/Laimory-android/blob/9bb48758cbb986f0e48794884376fe8a72e84862/app/src/main/java/com/soma369/laimory/navigation/LaimoryNavGraph.kt#L98),
[로그인 여부](https://github.com/soma17th-369/Laimory-android/blob/9bb48758cbb986f0e48794884376fe8a72e84862/app/src/main/java/com/soma369/laimory/crash/SignedInCrashKeyObserver.kt),
[푸시 로그](https://github.com/soma17th-369/Laimory-android/blob/9bb48758cbb986f0e48794884376fe8a72e84862/app/src/main/java/com/soma369/laimory/push/LaimoryFirebaseMessagingService.kt),
[수집 설정 설명](https://github.com/soma17th-369/Laimory-android/blob/9bb48758cbb986f0e48794884376fe8a72e84862/app/src/main/java/com/soma369/laimory/crash/CrashlyticsCrashReporter.kt).
SDK 근거: 20.0.6 소스의 [보고서 구성](https://github.com/firebase/firebase-android-sdk/blob/9a6b0c0f31ab56e3cdd59e32b38c72cb6b7f5b50/firebase-crashlytics/src/main/java/com/google/firebase/crashlytics/internal/common/CrashlyticsReportDataCapture.java),
[예외 메시지 취득](https://github.com/firebase/firebase-android-sdk/blob/9a6b0c0f31ab56e3cdd59e32b38c72cb6b7f5b50/firebase-crashlytics/src/main/java/com/google/firebase/crashlytics/internal/stacktrace/TrimmedThrowableData.java#L56).

확인한 앱 로그 호출에서는 기록 본문·사진·좌표·주소·이메일·닉네임·앱 로그인 토큰을 의도적으로
원격 로그에 넣는 동작을 찾지 못했다. 다만 모든 문자열에 공통 필터를 적용하는 구조는 아니므로
“어떤 경로에서도 개인정보가 들어가지 않는다” 또는 “모든 예외 메시지가 제거된다”고 약속하지 않는다.
이는 모든 오류 조합의 실행 검증이나 실제 전송 패킷 검사를 수행했다는 뜻이 아니다.

[Firebase 개인정보·보안 설명](https://firebase.google.com/support/privacy)에 따른 보유기간은
90일 보관 후 활성·백업 시스템에서 삭제 절차를 시작하는 것이다. 90일을 완전 삭제 시한으로 쓰지 않는다.
계정 정보의 탈퇴 후 5일 파기와 오류 진단 정보의 보유·삭제를 구분하고, 기존 정보의 삭제·처리정지
요구는 처리방침 제12조의 접수 경로를 유지한다.

## 2. 수탁자와 국외 이전 국가 대조

[Crashlytics 표준약관 전문](https://firebase.google.com/terms/crashlytics)은 APAC 사업자의 계약
상대방을 **Google Asia Pacific Pte. Ltd.**로 정한다. 한국 사업자에 적용되는 표준약관을 기준으로
제6조·제7조에 이 법인을 기재하며, FCM의 Google LLC와 구분한다. 별도 서면 계약이 있는 경우에는
그 실제 계약 상대방을 우선해야 한다. [Firebase DPA 제5.2조](https://firebase.google.com/terms/data-processing-terms)는
고객의 지시에 따른 서비스 제공·보안·모니터링 등의 처리 범위를 정한다.

[Firebase 글로벌 처리 위치 설명](https://firebase.google.com/support/privacy#data_storage_and_processing_locations)은
FCM과 Crashlytics 모두 Google 글로벌 인프라의 사용 대상에 포함한다. 따라서 미국 한 국가나
계약 상대방 소재지인 싱가포르 한 국가만 적지 않는다. 아래 자료를 서비스 적용 범위에 맞게 대조했다.

| 자료 | 포함·제외 기준 | 대조 결과 |
| --- | --- | --- |
| [Google Cloud 운영 리전·존](https://docs.cloud.google.com/compute/docs/regions-zones) | 현재 존의 Location 국가. 건설 예정 리전 제외 | 아래 Google 계열 재수탁자 국가 범위 안에 있음 |
| [Google Cloud 재수탁자](https://cloud.google.com/terms/subprocessors) | Google Group Subprocessors 중 전체 GCP 서비스 또는 Firebase 적용 행의 **Country where processing is performed**. 법인 등록 국가와 구분 | 중복 제거 40개국, 대한민국 포함 |
| [Firebase 재수탁자](https://firebase.google.com/terms/subprocessors) | Crashlytics 인프라의 Firebase, Inc. 및 Firebase 지원 업무와 Google 계열 재수탁자 연결 확인. Hosting 전용 Fastly 및 Firebase 적용 근거 없는 GCP 제3자 업체 제외 | 기존 확인 국가에 새 국가를 추가할 근거 없음. 제3자 업체의 미국 본사 소재지를 모든 실제 지원 국가의 증명으로 취급하지 않음 |
| [Google 데이터센터 위치](https://datacenters.google/locations/) | 공개 시설 목록과 개별 시설의 상태 설명을 함께 대조 | 우루과이 외에 위 40개에 없는 현재 시설 국가를 확인하지 못함. 건설 중으로 표시된 노르웨이 제외 |

Google 계열 재수탁자의 확인된 해외 39개국은 다음과 같다.

남아프리카공화국, 네덜란드, 뉴질랜드, 대만, 덴마크, 독일, 룩셈부르크, 말레이시아, 멕시코, 미국,
벨기에, 브라질, 사우디아라비아, 스웨덴, 스위스, 스페인, 슬로바키아, 싱가포르, 아르헨티나,
아일랜드, 영국, 오스트리아, 이스라엘, 이탈리아, 인도, 인도네시아, 일본, 체코, 칠레, 카타르,
캐나다, 태국, 포르투갈, 폴란드, 프랑스, 핀란드, 필리핀, 호주, 홍콩.

**기존 FCM 41개국과의 차이:** 위 39개국 + 대한민국 + 우루과이였다. 대한민국은 국외 목록에서
제외한다. 우루과이는 [Canelones 시설 상세 안내](https://datacenters.google/locations/canelones-uruguay/)가
미래형으로 설명하고 있으며, [우루과이 대통령실의 2026-04-14 자료](https://www.gub.uy/presidencia/comunicacion/publicaciones/presidente-orsi-visito-proyecto-google)도
공사 방문으로 설명한다. 목록에 노출된 사실만으로 가동 중이라고 단정하지 않는다.

처리방침에는 공개된 시설 범위를 누락하지 않도록 **40개 해외 위치(39개 확인국 + 우루과이)**를
유지하고 우루과이 상태의 불확실성을 명시했다. 이는 개별 오류 보고서가 40개국 전부로 전송된다는
확인 결과가 아니다. 공급자 공개 자료로 가능한 위치를 대조한 결과이며, 실제 처리 경로를 추적한
결과 또는 Firebase의 제품별 확정 국가 회신으로 표현하지 않는다.

## 3. 동의 없는 처리의 적용 판단

[개인정보 보호법 제15조 제1항 제4호](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1029335389)는
계약 이행뿐 아니라 계약 체결 과정에서 이용자의 요청에 따른 조치에 필요한 수집·이용도 포함한다.
현행 [제28조의8 제1항 제3호](https://law.go.kr/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1029331899)도
국외 위탁·보관에 대해 **계약의 체결 및 이행**을 명시한다. 가입 전이라는 사실만으로 이 근거를
배제하거나 일률적으로 별도 동의를 요구하지 않는다.

[개인정보위 「개인정보 처리 통합 안내서」 2025.7](https://www.pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=G010030000&nttId=11352)
41~47쪽은 계약 관계, 이용자의 예측 가능성, 합리적 필요성과 최소 수집을 함께 보도록 설명한다.
약관에 목적을 적는 것만으로 무관한 수집까지 허용되는 것은 아니다.

**이 앱에 대한 적용 판단:** 이용자가 요청한 가입·로그인·기록 서비스의 장애를 찾아 정상 제공하기
위한 진단이고, 오류 위치·환경·실행 단계·중복 및 영향 범위를 파악하는 일반 개인정보로 범위를
한정한다. 가입·로그인 과정의 진단도 계약 체결 과정에서 이용자가 요청한 서비스 제공에 부수되는
처리로 평가한다. 실제 항목과 앱 실행부터의 처리 시점을 #8에 공개하고, 광고·성향 분석·사용자 기록
원문의 분석을 진단 목적으로 추가하지 않는 현재 범위에서는 위 두 조항을 적용할 근거가 있다고 판단한다.

따라서 이 변경에 별도 Crashlytics 동의서나 전 사용자 재동의를 추가하는 방식을 채택하지 않는다.
이는 현재 목적·최소 수집 범위를 전제로 한 해석이다. 수집 목적을 확대하거나 민감정보·기록 원문을
진단 데이터로 의도적으로 추가한다면 이번 판단을 그대로 재사용할 수 없다.

## 4. 거부 안내의 적용 판단

제28조의8 제1항 제3호 가목을 적용하더라도 제2항의 공개사항을 모두 기재해야 하므로 **거부 방법·절차·효과를
생략하지 않는다**. [개인정보위 「개인정보 처리방침 작성지침」 2026.4](https://pipc.go.kr/np/cop/bbs/selectBoardArticle.do?bbsId=BS217&mCode=G010030020&nttId=12018)
45~48쪽은 계약 이행에 필요한 국외 백업을 회원 탈퇴로 거부하고 서비스 이용이 제한되는 예시를 제시한다.
전용 앱 토글을 모든 경우에 설치하라는 요건으로 해석하지 않는다.

현재 앱에서 실제 가능한 방법으로 **기기 설정에서 앱 삭제 → 해당 기기 추가 전송 중단 → 해당 기기 앱 이용 불가**를
안내한다. 알림 등록 해제는 FCM에 한정한다. 앱 삭제를 회원 탈퇴나 이미 전송된 정보의 즉시 삭제와
동일시하지 않으며, 삭제·처리정지 요청을 접수할 이메일을 유지한다. 이메일 요청만으로 특정 기기의
SDK 수집을 원격 중단할 수 있다고 약속하지 않는다.

앱 삭제 안내가 위 공개 요건에 맞는 방법이 될 수 있다고 판단한 근거는 실제 중단 가능성과 효과의
명시, 최소 진단의 계약상 필요성, 정보주체의 권리 행사 경로 유지다. 개인정보위 예시는 회원 탈퇴에
관한 것이므로 이를 Crashlytics 앱 삭제 방식에 대한 개별 승인이라고 인용하지 않는다.

## 5. 변경·검증 범위

개인정보 처리방침 제1·3·4·6·7조를 정리하고, 제9조의 자동 마스킹 문구는 실제 적용 대상인
인공지능 실행 추적정보로 한정한다. 앱 자동 오류 메시지에도 같은 마스킹이 적용된다고 읽히지 않게 한다.
문서 `1.0`·기존 시행일·URL을 유지하며 #5를 비롯한 다른 동의서와 Android 구현은 변경하지 않는다.
생성기 실행과 생성 HTML의 내용·표·metadata 검증은 게시 검증과 구분한다.

## 6. 게시 전 미결 — 이미 전송된 정보의 삭제 처리

공급자의 기본 90일 보유기간을 확인한 것만으로 탈퇴·유효한 삭제 요구 이후에도 항상 90일 동안
보유할 수 있다고 판단하지 않는다. 위 2025년 통합 안내서 105~106쪽의 파기 기준에 따라, 처리 목적이
소멸하거나 적법한 삭제 사유가 발생한 정보의 처리 절차를 별도로 확인해야 한다.

확인한 서버 탈퇴 경로의 `AccountErasureService`와 `PushRegistrationService.deleteAll`은
회원과 푸시 설치 식별자의 DB 연결을 삭제한다. Android의 `UnregisterCurrentPushInstallationUseCase`는
서버 등록만 해제하며 Firebase 설치 자체는 삭제하지 않는다고 명시한다. 확인한 코드에서는 이미
전송된 Crashlytics 진단 정보를 Google에 삭제 요청하는 동작을 찾지 못했다. 별도의 수동 운영 절차가
있는지는 확인되지 않았다.

게시 전에는 다음 사항을 확인하고 처리방침 제4조·제7조의 최종 문구와 일치시킨다.

- 삭제 요구를 접수·처리할 담당자와 처리 대상 식별 방법
- Google에 삭제를 요청하는 실제 절차 및 그 절차가 삭제하는 데이터의 범위
- 일반적인 90일 보유와 적법한 조기 삭제 사유가 발생한 경우의 처리 구분

자동화 구현을 필수 조건으로 정하는 것은 아니지만 실제 수행 가능한 절차가 필요하다. 현재 원문은
이 확인이 남은 검토안이며, 이메일 접수 경로가 있다는 이유로 Google 측 삭제까지 구현되었다고 보지 않는다.
