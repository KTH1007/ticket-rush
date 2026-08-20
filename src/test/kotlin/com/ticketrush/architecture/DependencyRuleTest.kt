package com.ticketrush.architecture

import com.querydsl.core.annotations.Generated
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.ticketrush"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class DependencyRuleTest {
    // 아직 domain/infrastructure/api 패키지가 없어 매칭 대상이 0개다.
    // ArchUnit 1.5.0은 매칭 대상 0개를 기본적으로 실패 처리한다(오타로 아무것도 못
    // 찾은 것과 구분이 안 되기 때문). 실제 도메인 모듈이 생기면 allowEmptyShould를
    // 지우고 이 안전장치를 다시 살려야 한다.
    @ArchTest
    val `도메인은 인프라 구현체와 API 계층을 의존하지 않는다`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infrastructure..", "..api..")
            .allowEmptyShould(true)

    // QueryDSL이 생성하는 Q타입(QEvent 등)은 엔티티와 같은 패키지(domain)에 생성되고
    // com.querydsl.. 타입을 그대로 참조한다. 우리가 작성한 코드가 아니라 kapt가
    // 기계적으로 만든 산출물이라 이 규칙의 검사 대상에서 제외한다.
    @ArchTest
    val `도메인은 웹과 저장소 기술을 의존하지 않는다`: ArchRule =
        noClasses()
            .that(resideInAPackage("..domain..").and(not(annotatedWith(Generated::class.java))))
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.data.redis..",
                "com.querydsl..",
            ).allowEmptyShould(true)

    @ArchTest
    val `API 계층은 리포지토리를 직접 호출하지 않는다`: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true)

    @ArchTest
    val `Port 인터페이스는 도메인 계층에 둔다`: ArchRule =
        classes()
            .that()
            .haveSimpleNameEndingWith("Port")
            .should()
            .resideInAPackage("..domain..")
            .allowEmptyShould(true)
}
