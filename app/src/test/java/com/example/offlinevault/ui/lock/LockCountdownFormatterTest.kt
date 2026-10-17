package com.example.offlinevault.ui.lock

import kotlin.test.Test
import kotlin.test.assertEquals

class LockCountdownFormatterTest {
    @Test
    fun rendersTheTerminalCountdownShape() {
        assertEquals("[████████░░] 24s", LockCountdownFormatter.format(24_000L))
        assertEquals("[░░░░░░░░░░] 0s", LockCountdownFormatter.format(0L))
    }
}
