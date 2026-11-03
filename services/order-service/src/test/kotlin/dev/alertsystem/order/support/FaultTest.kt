package dev.alertsystem.order.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaultTest {
    @Test
    fun `parses targeted directives with args`() {
        val parsed = FaultSpec.parse("shipping:latency=300, product:biz ,order:tech")
        assertEquals(
            listOf(
                FaultDirective("shipping", "latency", "300"),
                FaultDirective("product", "biz", null),
                FaultDirective("order", "tech", null),
            ),
            parsed,
        )
    }

    @Test
    fun `ignores malformed tokens and null header`() {
        assertEquals(emptyList(), FaultSpec.parse(null))
        assertEquals(listOf(FaultDirective("order", "biz", null)), FaultSpec.parse("garbage,:biz,order:,order:biz"))
    }

    @Test
    fun `keeps only directives for this service but forwards the raw header`() {
        val ctx = RequestContext.of(true, "order", "order:biz,shipping:tech", "run-1", "req-1")
        assertTrue(ctx.has("biz"))
        assertTrue(!ctx.has("tech"))
        assertEquals("order:biz,shipping:tech", ctx.forwardFault)
        assertEquals("run-1", ctx.testRunId)
        assertEquals("req-1", ctx.requestId)
    }

    @Test
    fun `disabled injection ignores header and does not forward it (spec I2)`() {
        val ctx = RequestContext.of(false, "order", "order:biz", "run-1", "req-1")
        assertTrue(ctx.faults.isEmpty())
        assertNull(ctx.forwardFault)
        assertEquals("req-1", ctx.requestId) // 관측 속성은 그대로 전달
    }
}
