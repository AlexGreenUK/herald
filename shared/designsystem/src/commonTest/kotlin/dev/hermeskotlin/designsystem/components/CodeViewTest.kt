package dev.hermeskotlin.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeViewTest {

    private fun lines(text: String) = splitLines(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun linesWithoutTheirBreaks() {
        assertEquals(listOf("a", "", "b"), lines("a\n\nb"))
        assertEquals(listOf("a", "b"), lines("a\r\nb\r\n"))
    }

    @Test
    fun aTrailingBreakMakesNoEmptyLastLine() {
        assertEquals(listOf("a", "b"), lines("a\nb\n"))
        assertEquals(listOf(""), lines(""))
        assertEquals(listOf("", ""), lines("\n\n"))
    }
}
