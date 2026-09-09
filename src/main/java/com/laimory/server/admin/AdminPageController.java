package com.laimory.server.admin;

import com.laimory.server.common.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** 정적 자원은 public static/ 밖에 두고 명시된 관리자 경로에서만 제공한다. */
@Hidden
@Controller
@ConditionalOnProperty(name = "APP_ADMIN_PORT")
public class AdminPageController {

    private final AdminServer server;

    public AdminPageController(AdminServer server) {
        this.server = server;
    }

    @GetMapping({"/admin", "/admin/", "/admin/index.html"})
    ResponseEntity<ClassPathResource> index() {
        return resource("index.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/admin/admin.js")
    ResponseEntity<ClassPathResource> script() {
        return resource("admin.js", MediaType.parseMediaType("text/javascript"));
    }

    @GetMapping("/admin/admin.css")
    ResponseEntity<ClassPathResource> stylesheet() {
        return resource("admin.css", MediaType.parseMediaType("text/css"));
    }

    @GetMapping("/admin/api/csrf")
    @ResponseBody
    ApiResponse<Bootstrap> csrf(CsrfToken token) {
        return ApiResponse.success(new Bootstrap(server.environment(), token.getHeaderName(), token.getToken()));
    }

    private ResponseEntity<ClassPathResource> resource(String name, MediaType type) {
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("admin/" + name));
    }

    record Bootstrap(String environment, String headerName, String token) { }
}
