import com.epages.restdocs.apispec.gradle.OpenApi3Task
import dev.detekt.gradle.Detekt
import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

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
    alias(libs.plugins.asciidoctor)
    alias(libs.plugins.restdocs.api.spec)
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
    // Swagger UI 서빙 전용으로만 쓴다. 자체 리플렉션 스캔은 꺼두고
    // openapi3 태스크가 만든 스펙(REST Docs로 검증됨)을 대신 읽게 한다.
    implementation(libs.springdoc.openapi.webmvc.ui)
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
    kapt(variantOf(libs.querydsl.apt) { classifier("jakarta") })
    kapt("jakarta.annotation:jakarta.annotation-api")
    kapt("jakarta.persistence:jakarta.persistence-api")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    // 테스트 전용 엔티티(BaseEntityTestProbe 등)의 Q타입도 kapt가 생성하게 한다.
    kaptTest(variantOf(libs.querydsl.apt) { classifier("jakarta") })

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
    testImplementation("org.springframework.boot:spring-boot-starter-restdocs")
    testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    // MockMvcRestDocumentationWrapper.document(...)가 .adoc 스니펫과 함께
    // openapi3 태스크가 읽는 resource-*.json도 같이 만들어준다.
    testImplementation(libs.restdocs.api.spec.mockmvc)
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
    // Dockerfile은 -Duser.timezone=Asia/Seoul을 강제하지만 로컬 실행/CI는 호스트
    // 기본 타임존을 그대로 쓴다. hibernate.jdbc.time_zone과 어긋나면 LocalDateTime.now()
    // 비교가 오프셋만큼 어긋나므로 여기서도 동일하게 맞춘다.
    systemProperty("user.timezone", "Asia/Seoul")

    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// REST Docs 스니펫 출력 위치. 컨트롤러 테스트의 document(...)가
// API별로 .adoc/.json 조각을 여기 쌓는다.
val snippetsDir = layout.buildDirectory.dir("generated-snippets")

tasks.named<Test>("test") {
    outputs.dir(snippetsDir)
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

// 테스트가 만든 스니펫을 모아 HTML 문서로 렌더링한다. {snippets} 속성으로
// .adoc 파일 안의 include::{snippets}/...가 실제 경로를 찾게 한다.
tasks.named<AsciidoctorTask>("asciidoctor") {
    inputs.dir(snippetsDir)
    dependsOn(tasks.named("test"))
    attributes(mapOf("snippets" to snippetsDir.get()))
}

openapi3 {
    setServer("http://localhost:8080")
    title = "Ticket Rush API"
    description = "티켓 예매 시스템 API 문서"
    version = project.version.toString()
    format = "yaml"
}

// openapi3 태스크는 afterEvaluate 시점에 등록돼 tasks.named("openapi3")로는
// 못 찾는다. withType은 나중에 등록되는 태스크도 잡아준다.
tasks.withType<OpenApi3Task>().configureEach {
    dependsOn(tasks.named("test"))
}

// REST Docs HTML과 openapi3 스펙을 jar 안 정적 리소스로 담는다.
// HTML은 /docs/index.html로, 스펙은 /openapi3.yml로 서빙된다.
tasks.named<BootJar>("bootJar") {
    dependsOn(tasks.named("asciidoctor"), tasks.withType<OpenApi3Task>())
    from(tasks.named<AsciidoctorTask>("asciidoctor").map { it.outputDir }) {
        into("static/docs")
    }
    from(layout.buildDirectory.dir("api-spec")) {
        into("static")
        rename("openapi3.yaml", "openapi3.yml")
    }
}

// 로컬 bootRun도 Dockerfile과 같은 타임존을 쓰게 맞춘다.
tasks.named<BootRun>("bootRun") {
    systemProperty("user.timezone", "Asia/Seoul")
    // bootRun은 bootJar를 안 타서 REST Docs/openapi3 산출물이 클래스패스에 없다.
    // 미리 만들어둔 build 산출물 경로를 알려줘서 LocalDocsConfig가 직접 서빙하게 한다.
    systemProperty(
        "ticket-rush.docs.asciidoc-dir",
        layout.buildDirectory
            .dir("docs/asciidoc")
            .get()
            .asFile.absolutePath,
    )
    systemProperty(
        "ticket-rush.docs.api-spec-dir",
        layout.buildDirectory
            .dir("api-spec")
            .get()
            .asFile.absolutePath,
    )
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
