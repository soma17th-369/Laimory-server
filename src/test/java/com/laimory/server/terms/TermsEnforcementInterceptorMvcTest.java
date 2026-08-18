package com.laimory.server.terms;

import static com.laimory.server.testsupport.AuthTestSupport.authenticatedUser;
import static com.laimory.server.testsupport.TestSubjects.id;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.push.controller.PushRegistrationController;
import com.laimory.server.push.controller.PushSettingController;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.push.PushComplianceClass;
import com.laimory.server.push.service.PushRegistrationService;
import com.laimory.server.push.service.PushSettingService;
import com.laimory.server.terms.controller.TermAgreementController;
import com.laimory.server.terms.service.TermAgreementService;
import com.laimory.server.terms.service.TermsEnforcementService;
import com.laimory.server.testsupport.AuthTestSupport;
import com.laimory.server.timeline.controller.TimelineController;
import com.laimory.server.timeline.controller.TimelineRecordController;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.service.DailyTimelineService;
import com.laimory.server.timeline.service.PhotoUploadService;
import com.laimory.server.timeline.service.TimelineDeletionService;
import com.laimory.server.timeline.service.TimelineDraftTaskListService;
import com.laimory.server.timeline.service.TimelineDraftTaskPollingService;
import com.laimory.server.timeline.service.TimelineDraftTaskService;
import com.laimory.server.timeline.service.TimelineEventEditService;
import com.laimory.server.timeline.service.TimelineSaveService;
import com.laimory.server.user.Provider;
import com.laimory.server.user.controller.UserController;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.service.SubjectMappingService;
import com.laimory.server.user.service.UserService;
import com.laimory.server.user.service.UserWithdrawalService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /a/api} 약관 gate interceptor의 MVC 경계 검증 — 실제 {@code *Api} interface의 annotation을
 * 통과해 LOGIN 기본 gate·exemption·TIMELINE_FIRST_CREATE 추가 gate가 controller/service 진입 전에
 * 동작하는지 고정한다(판정 내부 로직은 {@code TermsEnforcementServiceTest} 소유). 인프라 0.
 */
@WebMvcTest(controllers = {TimelineController.class, TimelineRecordController.class,
        PushRegistrationController.class, PushSettingController.class, UserController.class,
        TermAgreementController.class})
@Import({SecurityConfig.class, AuthTestSupport.JwtTokensTestConfig.class})
class TermsEnforcementInterceptorMvcTest {

    private static final long USER_ID = 7L;
    private static final UUID SUBJECT_ID = id(USER_ID);

    private static final String DRAFTS = "/a/api/v1/timeline/drafts";
    private static final String DRAFT_BODY = """
            {
              "recordDate": "2026-08-15",
              "recordAt": "2026-08-16T09:30:00",
              "recordTimeZone": "Asia/Seoul",
              "timelineWindow": {"startTime": "2026-08-15T00:00", "endTime": "2026-08-16T00:00"},
              "sourceItems": [
                {"itemType": "HEALTH", "rawId": "0197b1c2-0000-7000-8000-000000000041",
                 "startAt": "2026-08-15T00:00:00", "endAt": null,
                 "payload": {"metric": "STEPS", "value": "8500보"}}
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TermsEnforcementService termsEnforcementService;
    @MockitoBean
    private SubjectMappingService subjectMappingService;

    @MockitoBean
    private TimelineDraftTaskService timelineDraftTaskService;
    @MockitoBean
    private TimelineDraftTaskPollingService timelineDraftTaskPollingService;
    @MockitoBean
    private TimelineDraftTaskListService timelineDraftTaskListService;
    @MockitoBean
    private PhotoUploadService photoUploadService;
    @MockitoBean
    private DailyTimelineService dailyTimelineService;
    @MockitoBean
    private TimelineEventEditService timelineEventEditService;
    @MockitoBean
    private TimelineDeletionService timelineDeletionService;
    @MockitoBean
    private TimelineSaveService timelineSaveService;
    @MockitoBean
    private PushRegistrationService pushRegistrationService;
    @MockitoBean
    private PushSettingService pushSettingService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserWithdrawalService userWithdrawalService;
    @MockitoBean
    private TermAgreementService termAgreementService;

    @BeforeEach
    void resolveSubject() {
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);
    }

    @Test
    void loginNotAgreed_generalProtectedEndpointRejected403BeforeService() throws Exception {
        doThrow(new BusinessException(ExceptionType.TERMS_AGREEMENT_REQUIRED))
                .when(termsEnforcementService).requireAgreements(TermStage.LOGIN, USER_ID);

        mockMvc.perform(get("/a/api/v1/timeline/daily-records").with(authenticatedUser(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.code").value(-3001))
                .andExpect(jsonPath("$.header.message").isNotEmpty())
                .andExpect(jsonPath("$.body").doesNotExist());

        verifyNoInteractions(dailyTimelineService);
    }

    @Test
    void exemptOperations_neverConsultTermsGate_andRemainAccessible() throws Exception {
        // 동의 등록/이력·내 회원 조회·회원 탈퇴(#305)·push PUT/DELETE는 LOGIN gate 판정 자체를 타지
        // 않는다 — 미동의(gate가 403을 던질 상태)에서도 접근 가능함이 이 exemption의 의미다.
        when(userService.getProfile("v1", USER_ID))
                .thenReturn(User.of(Provider.KAKAO, "sub-303", null, "라이머"));
        when(termAgreementService.getHistory("v1", USER_ID)).thenReturn(List.of());
        String pushBody = "{\"firebaseInstallationId\":\"fid-303\"}";
        String consentBody = "{\"clientRequestId\":\"55555555-5555-4555-8555-555555555555\","
                + "\"consented\":false}";
        when(pushSettingService.getSettings("v1", SUBJECT_ID)).thenReturn(new PushSettingsResponse(
                true,
                new PushSettingsResponse.DailyReminder(false, "21:00", PushComplianceClass.ADVERTISING),
                new PushSettingsResponse.ConsentStatus(false, null),
                new PushSettingsResponse.ConsentStatus(false, null),
                List.of()));
        when(pushSettingService.applyAdvertisingConsent(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(pushSettingService.applyNightAdvertisingConsent(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        String agreementBody = "{\"agreements\":[{\"termType\":\"TERMS_OF_SERVICE\",\"version\":\"2026-08-15\"}]}";

        mockMvc.perform(get("/a/api/v1/users/me").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/a/api/v1/push-registrations").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(pushBody))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/a/api/v1/push-registrations").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(pushBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/a/api/v1/terms/agreements").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(agreementBody))
                .andExpect(status().isOk());
        mockMvc.perform(get("/a/api/v1/terms/agreements").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk());
        // #305: 미동의 사용자도 탈퇴할 수 있다 — LOGIN gate를 타지 않고 202까지 도달한다.
        mockMvc.perform(delete("/a/api/v1/users/me").with(authenticatedUser(USER_ID)))
                .andExpect(status().isAccepted());
        // #314: 미동의 상태에서도 수신 설정을 조회하고 광고 수신을 거부할 수 있어야 한다.
        mockMvc.perform(get("/a/api/v1/push-settings").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/a/api/v1/push-settings/enabled").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/a/api/v1/push-settings/daily-reminder/enabled").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/a/api/v1/push-settings/daily-reminder/time").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"time\":\"21:00\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/a/api/v1/push-settings/advertising-consent").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(consentBody))
                .andExpect(status().isOk());
        mockMvc.perform(put("/a/api/v1/push-settings/night-advertising-consent").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(consentBody))
                .andExpect(status().isOk());

        verifyNoInteractions(termsEnforcementService);
        // 미동의 상태에서도 계정 전환 PUT은 서비스까지 도달한다(재결합 자체는 service/persistence 테스트 소유).
        verify(pushRegistrationService).register("v1", SUBJECT_ID, "fid-303", null);
        verify(pushRegistrationService).unregister("v1", SUBJECT_ID, "fid-303");
        verify(userWithdrawalService).withdraw("v1", USER_ID);
    }

    @Test
    void unauthenticatedRequest_rejected401BeforeTermsGate() throws Exception {
        mockMvc.perform(get("/a/api/v1/timeline/daily-records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.code").value(-2001));

        verifyNoInteractions(termsEnforcementService);
    }

    @Test
    void timelineFirstCreateNotAgreed_draftCreateRejected403BeforeAnyWork() throws Exception {
        // LOGIN은 통과(no-op mock), TIMELINE_FIRST_CREATE만 미동의 — 검사는 검증·지오코딩·저장·dispatch 전이다.
        doThrow(new BusinessException(ExceptionType.TERMS_AGREEMENT_REQUIRED))
                .when(termsEnforcementService).requireAgreements(TermStage.TIMELINE_FIRST_CREATE, USER_ID);

        mockMvc.perform(post(DRAFTS).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.code").value(-3001));

        verifyNoInteractions(timelineDraftTaskService);
        // 기본 LOGIN gate도 함께 적용된다(LOGIN → 추가 stage 순).
        InOrder inOrder = Mockito.inOrder(termsEnforcementService);
        inOrder.verify(termsEnforcementService).requireAgreements(TermStage.LOGIN, USER_ID);
        inOrder.verify(termsEnforcementService).requireAgreements(TermStage.TIMELINE_FIRST_CREATE, USER_ID);
    }

    @Test
    void timelineFirstCreateNotAgreed_photoPresignRejected403BeforeS3() throws Exception {
        doThrow(new BusinessException(ExceptionType.TERMS_AGREEMENT_REQUIRED))
                .when(termsEnforcementService).requireAgreements(TermStage.TIMELINE_FIRST_CREATE, USER_ID);

        mockMvc.perform(post(DRAFTS + "/photo-uploads").with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photos\":[{\"contentType\":\"image/jpeg\",\"size\":1024}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.code").value(-3001));

        verifyNoInteractions(photoUploadService);
    }

    @Test
    void allStagesAgreed_draftCreateReachesService() throws Exception {
        // gate mock 기본 no-op = 전부 동의한 상태 — 요청이 서비스까지 도달한다.
        mockMvc.perform(post(DRAFTS).with(authenticatedUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(DRAFT_BODY))
                .andExpect(status().isAccepted());

        verify(termsEnforcementService).requireAgreements(TermStage.LOGIN, USER_ID);
        verify(termsEnforcementService).requireAgreements(TermStage.TIMELINE_FIRST_CREATE, USER_ID);
        verify(timelineDraftTaskService).createDraftTask(Mockito.eq("v1"), Mockito.eq(SUBJECT_ID),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void loginAgreed_generalProtectedEndpointPasses() throws Exception {
        when(dailyTimelineService.getDailyTimelines("v1", SUBJECT_ID))
                .thenReturn(new DailyTimelinesResponse(List.of()));

        mockMvc.perform(get("/a/api/v1/timeline/daily-records").with(authenticatedUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.code").value(0));

        verify(termsEnforcementService).requireAgreements(TermStage.LOGIN, USER_ID);
    }
}
