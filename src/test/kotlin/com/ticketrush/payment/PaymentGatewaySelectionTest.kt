package com.ticketrush.payment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.infrastructure.FakePaymentGatewayAdapter
import com.ticketrush.payment.infrastructure.TossPaymentGatewayAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration
import kotlin.test.Test

class PaymentGatewaySelectionTest {
    private val logs = ListAppender<ILoggingEvent>()
    private val paymentLogger = LoggerFactory.getLogger("com.ticketrush.payment") as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        paymentLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        paymentLogger.detachAppender(logs)
        logs.stop()
    }

    // 어댑터가 쓰는 설정 값 변환(Duration)을 앱과 같게 맞추고, 프로필과 설정 값만 바꿔 가며 어느 빈이 뜨는지 본다
    private fun runner(
        gateway: String?,
        vararg profiles: String,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }
            .withInitializer { it.environment.setActiveProfiles(*profiles) }
            .withPropertyValues(*listOfNotNull(gateway?.let { "$PAYMENT_GATEWAY_PROPERTY=$it" }).toTypedArray())
            .withUserConfiguration(
                FakePaymentGatewayAdapter::class.java,
                TossPaymentGatewayAdapter::class.java,
                TossHttpConfig::class.java,
                TossTimeoutPolicyCheck::class.java,
                PaymentGatewayPolicyCheck::class.java,
            ).withBean(TossProperties::class.java, { TossProperties(baseUrl = "http://localhost", secretKey = "k") })
            .withBean(
                PaymentPolicyProperties::class.java,
                { PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(5)) },
            )

    // 조건에서 던진 예외는 감싸이지 않고, 빈을 만들다 던진 예외는 감싸여 올라온다. 어느 쪽이든 마지막 원인의 메시지를 본다
    private fun Throwable?.rootMessage(): String = generateSequence(this) { it.cause }.last().message.orEmpty()

    @Test
    fun `설정 값이 없으면 컨텍스트가 뜨지 않고 메시지에 설정 이름과 허용값이 있다`() {
        runner(gateway = null, "local").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure.rootMessage()).contains(PAYMENT_GATEWAY_PROPERTY).contains("가 없습니다").contains("fake 또는 toss")
        }
    }

    @Test
    fun `모르는 값이면 컨텍스트가 뜨지 않고 메시지에 받은 값이 있다`() {
        runner(gateway = "tos", "local").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure.rootMessage()).contains("'tos'").contains("fake 또는 toss")
        }
    }

    @Test
    fun `fake이면 가짜 어댑터만 등록되고 Toss 빈은 없다`() {
        runner(gateway = "fake", "local").run {
            assertThat(it).hasSingleBean(PaymentGatewayPort::class.java)
            assertThat(it).hasSingleBean(FakePaymentGatewayAdapter::class.java)
            assertThat(it).doesNotHaveBean(TossPaymentGatewayAdapter::class.java)
            assertThat(it).doesNotHaveBean(TossHttpConfig::class.java)
            assertThat(it).doesNotHaveBean(TossTimeoutPolicyCheck::class.java)
        }
    }

    @Test
    fun `toss이면 Toss 어댑터와 부속 빈만 등록되고 가짜 어댑터는 없다`() {
        runner(gateway = "toss", "prod").run {
            assertThat(it).hasSingleBean(PaymentGatewayPort::class.java)
            assertThat(it).hasSingleBean(TossPaymentGatewayAdapter::class.java)
            assertThat(it).hasSingleBean(TossHttpConfig::class.java)
            assertThat(it).hasSingleBean(TossTimeoutPolicyCheck::class.java)
            assertThat(it).doesNotHaveBean(FakePaymentGatewayAdapter::class.java)
        }
    }

    @Test
    fun `값의 대소문자는 구분하지 않는다`() {
        runner(gateway = "TOSS", "prod").run { assertThat(it).hasSingleBean(TossPaymentGatewayAdapter::class.java) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["local", "docker", "test"])
    fun `fake는 개발용 프로필에서 뜬다`(profile: String) {
        runner(gateway = "fake", profile).run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `fake는 개발용 프로필이 아니면 기동에 실패하고 메시지에 허용 프로필이 있다`() {
        runner(gateway = "fake", "prod").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure.rootMessage()).contains("local, docker, test").contains("prod")
        }
    }

    @Test
    fun `프로필을 지정하지 않으면 기본 프로필 local이 적용돼 fake가 뜬다`() {
        runner(gateway = "fake").withPropertyValues("spring.profiles.default=local").run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `프로필을 지정하지 않고 기본 프로필도 개발용이 아니면 fake는 기동에 실패한다`() {
        runner(gateway = "fake").run { assertThat(it).hasFailed() }
    }

    @Test
    fun `toss는 어떤 프로필에서도 뜬다`() {
        listOf("prod", "docker", "local").forEach { profile ->
            runner(gateway = "toss", profile).run { assertThat(it).hasNotFailed() }
        }
    }

    // 예전에는 toss 프로필이 Toss를 켰다. 그 프로필만 주던 구성이 가짜로 뜨는 것을 막는다
    @Test
    fun `toss 프로필이 켜져 있는데 gateway가 fake이면 기동에 실패한다`() {
        runner(gateway = "fake", "local", "toss").run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure.rootMessage()).contains("toss 프로필")
        }
    }

    @Test
    fun `toss 프로필이 켜져 있어도 gateway가 toss이면 뜬다`() {
        runner(gateway = "toss", "docker", "toss").run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `환경 변수 TICKET_RUSH_PAYMENT_GATEWAY가 설정으로 읽힌다`() {
        runner(gateway = null, "prod")
            .withInitializer {
                val env = SystemEnvironmentPropertySource("test", mapOf("TICKET_RUSH_PAYMENT_GATEWAY" to "toss"))
                it.environment.propertySources.addLast(env)
            }.run { assertThat(it).hasSingleBean(TossPaymentGatewayAdapter::class.java) }
    }

    @Test
    fun `fake이면 눈에 띄는 WARN을 남긴다`() {
        runner(gateway = "fake", "local").run { assertThat(it).hasNotFailed() }

        assertThat(logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
            .anyMatch { it.contains("가짜 PG") && it.contains("모든 결제를 승인") }
    }

    @Test
    fun `toss이면 WARN을 남기지 않는다`() {
        runner(gateway = "toss", "prod").run { assertThat(it).hasNotFailed() }

        assertThat(logs.list.filter { it.level == Level.WARN }).isEmpty()
    }
}
