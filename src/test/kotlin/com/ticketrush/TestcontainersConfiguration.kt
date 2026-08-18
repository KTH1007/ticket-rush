package com.ticketrush

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    // docker-compose.yml과 같은 이미지 태그를 쓴다. 테스트와 실제 환경의
    // DB/캐시 버전이 다르면 버전 특이 동작을 테스트가 놓칠 수 있다.
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379)
}
