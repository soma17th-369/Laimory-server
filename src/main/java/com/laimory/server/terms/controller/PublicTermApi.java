package com.laimory.server.terms.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.dto.TermListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 공개 약관 조회 API의 문서·계약(구현은 {@link PublicTermController}).
 *
 * <p>로그인 전 동의 화면에서도 필요하므로 public {@code /api}에 둔다 — bearer requirement가 없다.
 * 인증 동의 계약({@code /a/api/{version}/terms/agreements})은 {@link TermAgreementApi}로 분리해 public과
 * 보호 operation을 한 interface에 섞지 않는다(class-level bearer 문서와 security prefix 정합 유지).
 *
 * <p>버전은 {@code @PathVariable applicationVersion}으로 받아 그대로 Service에 넘긴다 — 버전별 분기는 Service 책임.
 */
@Tag(name = "Terms", description = "약관 — 종류별 현재 유효 약관 공개 조회")
@RequestMapping(ApiUrls.API_URL + "/terms")
public interface PublicTermApi {

    @Operation(summary = "현재 유효 약관 조회",
            description = "반복 query termTypes로 요청한 종류의 현재 유효 약관을 서버가 정의한 화면 순서로 "
                    + "반환한다. 각 종류의 "
                    + "현재 문서는 효력 시작(effectiveAt, Asia/Seoul 벽시계)이 지난 최신 버전이다. "
                    + "아직 유효한 문서가 없는 종류는 목록에서 빠지며, 전부 없으면(활성화 전) 404가 아니라 "
                    + "빈 배열이다. 원문은 응답에 담기지 않는다 — contentUrl은 그 버전의 원문 page HTTPS "
                    + "주소이며 클라이언트가 WebView로 연다. 동의 등록 시 이 응답의 (termType, version)을 "
                    + "그대로 회신한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공 — `body.terms`는 화면 순서 고정(활성화 전이면 빈 배열)",
                    useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — termTypes 누락·빈 배열·미지원 값")
    })
    @GetMapping
    ResponseEntity<ApiResponse<TermListResponse>> getCurrentTerms(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(description = "조회할 약관 종류(필수, 같은 query key를 반복)",
                    example = "TERMS_OF_SERVICE")
            @RequestParam("termTypes") List<TermType> termTypes);
}
