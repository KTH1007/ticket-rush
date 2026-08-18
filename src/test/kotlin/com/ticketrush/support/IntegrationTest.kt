package com.ticketrush.support

import com.redis.testcontainers.RedisContainer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@ActiveProfiles("test")
// object/interface로 바꾸라는 두 제안 다 오탐이다. 이 클래스는 상속해서
// 쓰는 게 목적이라 object는 안 되고, @SpringBootTest/@ActiveProfiles를
// 서브클래스가 물려받는 건 Spring이 공식적으로 지원하는 클래스 상속 기반
// 패턴이라 interface로는 같은 방식으로 동작하지 않는다.
@Suppress("UtilityClassWithPublicConstructor", "AbstractClassCanBeInterface")
abstract class IntegrationTest {
    companion object {
        // 싱글턴 재사용. @Testcontainers 방식은 클래스마다 컨테이너를 새로 띄워
        // 클래스가 늘면 기동 시간이 빌드 전체를 지배한다.
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("ticketrush")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true)
                .apply { start() }

        private val redis =
            RedisContainer(DockerImageName.parse("redis:8-alpine"))
                .withReuse(true)
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getRedisHost)
            registry.add("spring.data.redis.port") { redis.redisPort }
        }
    }
}
