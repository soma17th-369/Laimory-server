package com.laimory.server.admin;

import com.laimory.server.auth.security.ApiErrorResponseWriter;
import com.laimory.server.common.error.ExceptionType;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration {

    @Bean
    AdminServer adminServer(Environment environment) {
        return new AdminServer(environment);
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnectorCustomizer(AdminServer server) {
        return server::customize;
    }

    @Bean
    FilterRegistrationBean<AdminRequestGuardFilter> adminRequestGuard(AdminServer server,
                                                                     ApiErrorResponseWriter errors) {
        FilterRegistrationBean<AdminRequestGuardFilter> registration =
                new FilterRegistrationBean<>(new AdminRequestGuardFilter(server, errors));
        // TrustedEdge·TransactionId 뒤, Spring Session·Security 앞. 거절도 기존 access log에 남긴다.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD);
        return registration;
    }

    @Bean
    @Order(50)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, ApiErrorResponseWriter errors) throws Exception {
        http.securityMatcher(AdminRequestGuardFilter::isAdminRequest)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> { }) // 기본 HttpSessionCsrfTokenRepository + XOR token 보호를 유지한다.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling -> handling.accessDeniedHandler((request, response, exception) ->
                        errors.write(request, response, ExceptionType.ADMIN_REQUEST_REJECTED)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
