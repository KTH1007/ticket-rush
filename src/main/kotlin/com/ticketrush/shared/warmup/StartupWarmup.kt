package com.ticketrush.shared.warmup

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

// 기동 직후 첫 요청이 느린 것은 JVM과 프레임워크가 덜 데워져서다. readiness가 UP이 되기 전에 읽기 경로를 자기 자신에게 불러 데운다.
// ApplicationRunner는 readiness가 ACCEPTING_TRAFFIC으로 바뀌기 전에 끝나므로, 이 동안 헬스체크가 실패해 앞단이 트래픽을 보내지 않는다.
// 줄서기, 홀드, 결제 같은 쓰기 경로는 운영 데이터와 알림에 부작용이 생겨서 부르지 않는다. 예열이 실패해도 기동은 막지 않는다
@Component
@ConditionalOnProperty(prefix = "ticket-rush.warmup", name = ["enabled"], havingValue = "true")
class StartupWarmup(
    private val props: WarmupProperties,
    private val environment: Environment,
) : ApplicationRunner {
    data class Result(
        val requests: Int,
        val failures: Int,
    )

    private class Target(
        val client: HttpClient,
        val baseUrl: String,
    )

    private class Tally {
        val requests = AtomicInteger()
        val failures = AtomicInteger()
    }

    private data class Path(
        val value: String,
        val queueToken: String? = null,
    )

    private val mapper = JsonMapper.builder().build()

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        val port = environment.getProperty("local.server.port")?.toIntOrNull()
        if (port == null) {
            logger.warn { "서버 포트를 몰라 예열을 건너뜁니다" }
            return
        }
        try {
            val result = warm("http://127.0.0.1:$port")
            logger.info { "예열 완료: 요청 ${result.requests}건, 실패 ${result.failures}건, ${props.duration.toSeconds()}초" }
        } catch (e: Exception) {
            logger.warn(e) { "예열에 실패했지만 기동은 계속합니다" }
        }
    }

    internal fun warm(baseUrl: String): Result {
        val client =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build()
        val target = Target(client, baseUrl)
        val paths = pathsFor(target)
        val tally = Tally()
        val deadline = System.nanoTime() + props.duration.toNanos()
        val pool = Executors.newFixedThreadPool(props.concurrency) { task -> Thread(task, "warmup").apply { isDaemon = true } }
        repeat(props.concurrency) { worker -> pool.execute { hit(target, paths, worker, deadline, tally) } }
        pool.shutdown()
        pool.awaitTermination(props.duration.toSeconds() + AWAIT_MARGIN_SECONDS, TimeUnit.SECONDS)
        return Result(tally.requests.get(), tally.failures.get())
    }

    // 정해진 시간까지 경로를 돌려 가며 부른다. 워커마다 시작 위치를 달리해 같은 경로에 몰리지 않게 한다
    private fun hit(
        target: Target,
        paths: List<Path>,
        worker: Int,
        deadline: Long,
        tally: Tally,
    ) {
        var round = worker
        while (System.nanoTime() < deadline) {
            val path = paths[round % paths.size]
            round++
            tally.requests.incrementAndGet()
            if (!send(target, path)) tally.failures.incrementAndGet()
        }
    }

    // 목록으로 실제 공연 id를 얻어 공연별 읽기 경로를 만든다. 공연이 없으면 목록만 부른다
    private fun pathsFor(target: Target): List<Path> {
        val paths = mutableListOf(Path(EVENTS))
        eventIds(target).forEach { id ->
            paths += Path("$EVENTS/$id")
            paths += Path("$EVENTS/$id/queues/me", queueToken = "warmup")
            // 배치는 응답이 커서 비용이 크다. 상태보다 드물게 섞는다
            repeat(STATUS_PER_LAYOUT) { paths += Path("$EVENTS/$id/seats/status") }
            paths += Path("$EVENTS/$id/seats/layout")
        }
        return paths
    }

    @Suppress("TooGenericExceptionCaught")
    private fun eventIds(target: Target): List<Long> =
        try {
            val response = target.client.send(request(target, Path("$EVENTS?size=$MAX_EVENTS")), HttpResponse.BodyHandlers.ofString())
            mapper
                .readTree(response.body())
                .path("content")
                .mapNotNull { node -> node.path("id").takeIf { it.isNumber }?.asLong() }
        } catch (e: Exception) {
            logger.warn(e) { "공연 목록을 읽지 못해 목록만 예열합니다" }
            emptyList()
        }

    // 401, 404 같은 정상 응답도 경로를 데우는 데는 충분하다. 서버 오류와 연결 실패만 실패로 센다
    @Suppress("TooGenericExceptionCaught")
    private fun send(
        target: Target,
        path: Path,
    ): Boolean =
        try {
            target.client.send(request(target, path), HttpResponse.BodyHandlers.discarding()).statusCode() < SERVER_ERROR
        } catch (e: Exception) {
            logger.debug(e) { "예열 요청 실패: ${path.value}" }
            false
        }

    private fun request(
        target: Target,
        path: Path,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(target.baseUrl + path.value)).timeout(REQUEST_TIMEOUT).GET()
        builder.header("Accept-Encoding", "gzip")
        path.queueToken?.let { builder.header("X-Queue-Token", it) }
        return builder.build()
    }

    companion object {
        private const val EVENTS = "/api/events"
        private const val MAX_EVENTS = 3
        private const val STATUS_PER_LAYOUT = 8
        private const val SERVER_ERROR = 500
        private const val AWAIT_MARGIN_SECONDS = 10L
        private val CONNECT_TIMEOUT = Duration.ofSeconds(2)
        private val REQUEST_TIMEOUT = Duration.ofSeconds(10)
    }
}
