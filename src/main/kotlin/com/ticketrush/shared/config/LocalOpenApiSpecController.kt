package com.ticketrush.shared.config

import org.springframework.context.annotation.Profile
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File

// openapi3 태스크의 실제 산출물 파일명은 openapi3.yaml이다(bootJar만 openapi3.yml로 바꿔 담는다).
// 정적 리소스 핸들러는 파일명이 그대로 일치해야 찾을 수 있어서, 여기선 직접 읽어 서빙한다.
@RestController
@Profile("local")
class LocalOpenApiSpecController {
    @GetMapping("/openapi3.yml", produces = ["application/yaml"])
    fun openapi3Spec(): ResponseEntity<FileSystemResource> {
        val dir = System.getProperty("ticket-rush.docs.api-spec-dir") ?: return ResponseEntity.notFound().build()
        val file = File(dir, "openapi3.yaml")
        if (!file.exists()) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(FileSystemResource(file))
    }
}
