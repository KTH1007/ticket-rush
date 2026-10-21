package com.ticketrush.support

import com.redis.testcontainers.RedisContainer
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.transaction.TestTransaction
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@ActiveProfiles("test")
// object/interface/일반 클래스로 바꾸라는 제안은 다 오탐이다. 이 클래스는 상속해서
// 쓰는 게 목적이라 object와 일반 클래스는 안 되고, @SpringBootTest/@ActiveProfiles를
// 서브클래스가 물려받는 건 Spring이 공식적으로 지원하는 클래스 상속 기반
// 패턴이라 interface로는 같은 방식으로 동작하지 않는다.
@Suppress("UtilityClassWithPublicConstructor", "AbstractClassCanBeInterface", "AbstractClassCanBeConcreteClass")
abstract class IntegrationTest {
    // lateinit으로 주입받는 필드라 val로 못 바꾼다
    @Suppress("VarCouldBeVal")
    @Autowired
    private lateinit var rollbackGuardEntityManager: EntityManager

    // 롤백되는 테스트는 보류 중인 변경이 DB로 안 나가 CHECK 제약이 평가되지 않는다. 롤백 직전에 flush해 그런 위반을 드러낸다.
    // 롤백 테스트 안에서 일부러 실패시킨 엔티티는 세션에 남아 이 flush를 깨뜨리므로 그 테스트가 clear한다
    @AfterEach
    fun flushPendingChangesBeforeRollback() {
        if (TestTransaction.isActive()) rollbackGuardEntityManager.flush()
    }

    companion object {
        // 싱글턴 재사용. @Testcontainers 방식은 클래스마다 컨테이너를 새로 띄워
        // 클래스가 늘면 기동 시간이 빌드 전체를 지배한다.
        // 운영 SQL 스크립트를 psql로 그대로 실행하는 테스트가 컨테이너에 접근한다
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("ticketrush")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true)
                // 컨텍스트마다 풀이 20개씩 잡혀 설정이 다른 컨텍스트가 늘면 기본 한도(100)를 넘는다. fsync=off는 기본 명령이라 유지한다
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300")
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
