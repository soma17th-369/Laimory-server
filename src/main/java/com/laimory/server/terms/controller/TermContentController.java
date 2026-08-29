package com.laimory.server.terms.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 버전별 약관 원문 page — Markdown source에서 미리 생성해 classpath에 넣은 불변 HTML만 전달한다.
 *
 * <p>약관 API와 DB는 원문을 저장·렌더링하지 않고 이 공개 page의 URL만 다룬다. 경로 변수는 traversal이
 * 불가능한 slug와 {@code MAJOR.MINOR} 버전으로 제한하고, 해당 build artifact가 없으면 404다.
 */
@Hidden
@RestController
@RequestMapping("/terms")
public class TermContentController {

    private static final String RESOURCE_ROOT = "terms-content/terms/";
    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final CacheControl IMMUTABLE_VERSION_CACHE = CacheControl.maxAge(Duration.ofDays(365))
            .cachePublic()
            .immutable();

    @GetMapping(value = "/{slug:[a-z0-9-]+}/{version:\\d+\\.\\d+}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> getTermContent(@PathVariable String slug,
                                                   @PathVariable String version) throws IOException {
        Resource content = new ClassPathResource(RESOURCE_ROOT + slug + "/" + version);
        if (!content.exists() || !content.isReadable()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }

        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .contentLength(content.contentLength())
                .cacheControl(IMMUTABLE_VERSION_CACHE)
                .body(content);
    }
}
