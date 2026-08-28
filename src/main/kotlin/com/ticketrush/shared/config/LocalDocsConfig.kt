package com.ticketrush.shared.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// bootRun은 bootJar를 안 타서 REST Docs 산출물이 클래스패스에 없다.
// 로컬 개발 편의를 위해 build 산출물을 직접 정적 리소스로 노출한다.
// local 프로필 전용이라 packaged jar(운영/도커)에는 영향 없다.
// 작업 디렉토리 기준 상대경로라 IDE 실행 버튼/bootRun 어느 쪽이든 동일하게 동작한다.
@Configuration
@Profile("local")
class LocalDocsConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/docs/**").addResourceLocations("file:build/docs/asciidoc/")
    }
}
