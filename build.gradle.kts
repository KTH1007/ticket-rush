import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // allopen. Kotlin 클래스는 기본 final이라 프록시 생성이 불가능하다.
    // 없으면 @Transactional이 조용히 무시된다.
    alias(libs.plugins.kotlin.spring)
    // noarg. JPA가 요구하는 기본 생성자를 @Entity 클래스에 생성한다.
    alias(libs.plugins.kotlin.jpa)
    // QueryDSL Q타입 생성용 어노테이션 프로세서 브릿지
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

group = "com.ticketrush"
version = "0.0.1-SNAPSHOT"
description = "ticket-rush"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

kotlin {
    compilerOptions {
        // compileJava와 맞추지 않으면 타겟 불일치 경고가 나고,
        // Kotlin 버전이 낮으면 하위 버전으로 폴백한다.
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
        )
    }
}

// kotlin-spring은 @Component, @Configuration, @Transactional 같은
// 스프링 어노테이션만 open으로 바꾼다. kotlin-jpa는 기본 생성자만 만들고
// open 처리를 하지 않으므로, Hibernate가 지연 로딩 프록시를 만들 수 있도록
// JPA 어노테이션을 명시적으로 추가한다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories { mavenCentral() }

// Spring Boot 4에서 테스트 스타터가 모듈별로 쪼개져 mockito가 여러 경로로 들어온다.
// 한 스타터에만 exclude를 걸면 다른 스타터를 통해 다시 유입되므로 전역으로 건다.
// MockK를 쓰는 이유는 Mockito가 Kotlin의 final 클래스를 mock하려면
// 별도 설정이 필요하지만 MockK는 기본 지원하기 때문이다.
configurations {
    testImplementation {
        exclude(module = "mockito-core")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.apache.commons:commons-pool2")

    implementation(libs.resilience4j.spring.boot4)

    implementation(platform(libs.modulith.bom))
    implementation(libs.modulith.starter.jpa)
    implementation(libs.modulith.actuator) // /actuator/modulith 노출용
    runtimeOnly(libs.modulith.starter.insight)

    implementation(libs.querydsl.jpa)
    kapt(libs.querydsl.apt)
    kapt("jakarta.annotation:jakarta.annotation-api")
    kapt("jakarta.persistence:jakarta.persistence-api")
    kapt("org.springframework.boot:spring-boot-configuration-processor")

    implementation(libs.kotlin.logging)
    implementation(libs.logstash.encoder)

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    testImplementation(libs.modulith.test)
    testImplementation(libs.mockk)
    testImplementation(libs.archunit)

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.testcontainers.redis)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// testLogging은 모든 Test 태스크에 공통으로 적용해도 안전하다.
// 태그 필터는 여기 두지 않는다. withType<Test>는 나중에 등록되는
// concurrencyTest에도 그대로 적용되는데, useJUnitPlatform은 여러 번 호출하면
// 옵션이 누적된다. excludeTags("concurrency")가 여기 있으면 concurrencyTest의
// includeTags("concurrency")와 충돌하고, JUnit Platform은 같은 태그가
// include/exclude에 동시에 있으면 exclude를 우선시한다 — concurrencyTest가
// 실행은 되지만 태그 붙은 테스트를 실제로는 하나도 안 도는 상태가 된다.
// (경고: "The tag 'concurrency' is both included and excluded" 로 실측 확인)
tasks.withType<Test> {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        // 동시성 테스트는 스레드를 여러 개 띄워 느리고 CPU를 많이 쓴다.
        // 매 빌드에 넣으면 피드백 주기가 길어진다.
        excludeTags("concurrency")
    }
}

// 동시성 테스트만 골라 돌린다. 같은 컴파일 결과물을 재사용하므로
// 별도 컴파일 없이 ./gradlew concurrencyTest로 실행된다.
tasks.register<Test>("concurrencyTest") {
    useJUnitPlatform { includeTags("concurrency") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.test)

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

detekt {
    // 커스텀 설정이 기본 룰셋을 덮어쓰는 게 아니라 위에 얹힌다.
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        checkstyle.required.set(true)
        sarif.required.set(false)
        markdown.required.set(false)
    }
}

// 정적 분석과 포맷 검사를 check에 물린다. build가 check에 의존하므로
// 위반이 있으면 ./gradlew build가 실패한다.
//
// detekt(타입 리졸루션 없음) 대신 detektMain/detektTest를 쓴다. ForbiddenMethodCall처럼
// 완전한 이름 분석이 필요한(@RequiresFullAnalysis) 규칙은 타입 리졸루션 없이는
// 조용히 무시되고 위반을 잡지 못한다 — 직접 프로브 파일로 검증함.
//
// ktlintFormat은 여기 물리지 않는다. 빌드가 소스를 수정하면 CI에서
// 포맷이 자동으로 고쳐지고 통과해버려 게이트가 무의미해진다.
// 포맷 수정은 사람이 ./gradlew ktlintFormat으로 직접 부르거나 IDE가 한다.
tasks.named("check") {
    dependsOn("detektMain", "detektTest", "ktlintCheck")
}
