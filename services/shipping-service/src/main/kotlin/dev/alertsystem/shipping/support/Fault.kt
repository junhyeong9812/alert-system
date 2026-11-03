package dev.alertsystem.shipping.support

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// 실험 전용 장애 주입. X-Fault: "<service>:<kind>[=<arg>],..." (예: "shipping:latency=300,product:biz")
// kind: biz | tech | latency=<ms> | crash-after-commit | compensation-fail | dup-send
// 이 파일은 order/product/shipping에 패키지명만 바꿔 복사되어 있다 (서비스 독립 프로젝트).

data class FaultDirective(val service: String, val kind: String, val arg: String?)

object FaultSpec {
    fun parse(raw: String?): List<FaultDirective> =
        raw.orEmpty().split(',').mapNotNull { token ->
            val parts = token.trim().split(':', limit = 2)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
            val kv = parts[1].split('=', limit = 2)
            FaultDirective(parts[0].trim(), kv[0].trim(), kv.getOrNull(1)?.trim())
        }
}

// 요청 단위 컨텍스트. testRunId/requestId는 관측 속성 — 아키텍처 로직에서 읽지 않는다 (spec I1).
class RequestContext(
    val faults: List<FaultDirective>,
    val forwardFault: String?,
    val testRunId: String?,
    val requestId: String?,
) {
    fun has(kind: String) = faults.any { it.kind == kind }
    fun arg(kind: String) = faults.firstOrNull { it.kind == kind }?.arg

    companion object {
        private val holder = ThreadLocal<RequestContext>()
        val EMPTY = RequestContext(emptyList(), null, null, null)

        fun current(): RequestContext = holder.get() ?: EMPTY

        // 장애 주입이 꺼져 있으면 헤더를 무시하고 하위로 전달하지도 않는다 (spec I2).
        fun of(enabled: Boolean, service: String, fault: String?, testRunId: String?, requestId: String?) =
            RequestContext(
                faults = if (enabled) FaultSpec.parse(fault).filter { it.service == service } else emptyList(),
                forwardFault = if (enabled) fault else null,
                testRunId = testRunId,
                requestId = requestId,
            )

        internal fun <T> with(ctx: RequestContext, block: () -> T): T {
            holder.set(ctx)
            try {
                return block()
            } finally {
                holder.remove()
            }
        }
    }
}

object Headers {
    const val FAULT = "X-Fault"
    const val TEST_RUN_ID = "X-Test-Run-Id"
    const val REQUEST_ID = "X-Request-Id"
}

@Component
class FaultFilter(
    @Value("\${fault-injection.enabled:false}") private val enabled: Boolean,
    @Value("\${app.service-name}") private val serviceName: String,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val ctx = RequestContext.of(
            enabled, serviceName,
            request.getHeader(Headers.FAULT),
            request.getHeader(Headers.TEST_RUN_ID),
            request.getHeader(Headers.REQUEST_ID),
        )
        RequestContext.with(ctx) {
            ctx.arg("latency")?.toLongOrNull()?.let { Thread.sleep(it) }
            chain.doFilter(request, response)
        }
    }
}

object Crash {
    // 커밋 직후·알림 전송 전 프로세스 종료 (FI-07, dual write 공백 재현). compose restart 정책으로 복귀한다.
    fun now(): Nothing {
        Runtime.getRuntime().halt(137)
        error("unreachable")
    }
}
