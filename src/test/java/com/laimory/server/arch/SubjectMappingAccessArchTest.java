package com.laimory.server.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserSubjectLinkRepository;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * subject mapping 내부(repository·lookup key deriver)는 {@link SubjectMappingService}만 의존해야
 * 한다(#282, 계획 §2.3). 다른 application service가 lookup key 파생이나 mapping 행을 직접 다루면
 * raw userId↔subject 해석 책임이 service 경계 밖으로 새므로 빌드에서 차단한다.
 * ({@code RedisAccessArchTest}와 같은 형태 — 테스트 코드는 검사 대상에서 제외된다.)
 */
@AnalyzeClasses(packages = "com.laimory.server", importOptions = ImportOption.DoNotIncludeTests.class)
class SubjectMappingAccessArchTest {

    @ArchTest
    static final ArchRule subject_mapping_internals_only_through_service =
            noClasses()
                    .that(not(belongToAnyOf(
                            SubjectMappingService.class,
                            UserSubjectLinkRepository.class,
                            SubjectLookupKeyDeriver.class)))
                    .should().dependOnClassesThat(belongToAnyOf(
                            UserSubjectLinkRepository.class,
                            SubjectLookupKeyDeriver.class))
                    .because("subject mapping repository와 lookup key deriver는 "
                            + "SubjectMappingService만 의존해야 한다");
}
