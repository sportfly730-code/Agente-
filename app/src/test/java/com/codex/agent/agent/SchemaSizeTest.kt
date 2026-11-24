package com.codex.agent.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaSizeTest {
    @Test
    fun compactSchemaIsMuchSmaller() {
        val full = ToolDefinitions.getAllToolsSchema("").toString()
        val compact = ToolDefinitions.getAllToolsSchemaCompact("").toString()
        println("FULL schema chars: ${full.length}")
        println("COMPACT schema chars: ${compact.length}")
        println("Reduction: ${(100 * (1 - compact.length.toDouble() / full.length)).toInt()}%")
        assertTrue("Compact must be at least 40% smaller", compact.length < full.length * 0.6)
    }
}
