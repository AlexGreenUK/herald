package dev.hermeskotlin.designsystem.components

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableColumnsTest {
    @Test
    fun shortCellsKeepTheWidthTheyWant() {
        val widths = tableColumnWidths(min = intArrayOf(40, 60), max = intArrayOf(50, 90), available = 1000)
        assertContentEquals(intArrayOf(50, 90), widths)
    }

    @Test
    fun anEmojiColumnStaysSlimWhileTheWordyOnesShare() {
        // An emoji column (40 px), a short name column and a long sentence column, on a 700 px phone.
        val widths = tableColumnWidths(min = intArrayOf(40, 120, 150), max = intArrayOf(40, 300, 1200), available = 700)
        assertEquals(40, widths[0])
        assertEquals(700, widths.sum())
        assertTrue(widths[2] > widths[1], "the sentence column gets more than the name column")
        assertTrue(widths[1] >= 120 && widths[2] >= 150, "no column goes below its longest word")
    }

    @Test
    fun theWholeWidthIsUsedWhenTheCellsWantMore() {
        val widths = tableColumnWidths(min = intArrayOf(30, 30, 30), max = intArrayOf(500, 500, 500), available = 901)
        assertEquals(901, widths.sum())
    }

    @Test
    fun columnsOfEqualNeedShareEqually() {
        val widths = tableColumnWidths(min = intArrayOf(50, 50), max = intArrayOf(500, 500), available = 600)
        assertContentEquals(intArrayOf(300, 300), widths)
    }

    @Test
    fun whenTheLongestWordsDontFitTheyKeepTheirWidthAndTheTableScrolls() {
        val widths = tableColumnWidths(min = intArrayOf(200, 300, 400), max = intArrayOf(500, 600, 900), available = 700)
        assertContentEquals(intArrayOf(200, 300, 400), widths)
    }

    @Test
    fun oneColumnTakesWhatItWantsUpToTheRoom() {
        assertContentEquals(intArrayOf(300), tableColumnWidths(intArrayOf(50), intArrayOf(300), 800))
        assertContentEquals(intArrayOf(800), tableColumnWidths(intArrayOf(50), intArrayOf(2000), 800))
    }

    @Test
    fun anUnboundedWidthGivesEveryColumnItsWish() {
        val widths = tableColumnWidths(min = intArrayOf(10, 20), max = intArrayOf(400, 900), available = Int.MAX_VALUE)
        assertContentEquals(intArrayOf(400, 900), widths)
    }

    @Test
    fun noColumnsIsFine() {
        assertEquals(0, tableColumnWidths(IntArray(0), IntArray(0), 500).size)
    }
}
