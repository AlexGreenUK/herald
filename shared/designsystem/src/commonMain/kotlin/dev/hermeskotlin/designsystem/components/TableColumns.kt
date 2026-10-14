package dev.hermeskotlin.designsystem.components

/**
 * The width of each column of a table, in pixels, given the narrowest each can be ([min], its longest
 * word) and the widest it would like to be ([max], its longest cell on one line), within [available].
 *
 * - When every column fits at its wanted width, it gets that, so a table of short cells stays narrow.
 * - When they don't, each column keeps its narrowest width and the room left is shared in proportion to
 *   how much more each would like. A column of emoji or numbers stays slim and the wordy ones wrap.
 * - When even the narrowest widths don't fit, the columns take those, and the table scrolls sideways.
 */
internal fun tableColumnWidths(min: IntArray, max: IntArray, available: Int): IntArray {
    val wanted = IntArray(min.size) { maxOf(min[it], max[it]) }
    if (wanted.sum() <= available) return wanted
    val floor = min.sum()
    if (floor >= available) return min.copyOf()

    val extra = available - floor
    val excess = IntArray(min.size) { wanted[it] - min[it] }
    val excessTotal = excess.sum()
    val widths = IntArray(min.size) { min[it] + (extra.toLong() * excess[it] / excessTotal).toInt() }

    // Rounding down leaves a few pixels over; hand them to the columns that wanted more, last first.
    var left = available - widths.sum()
    var column = min.lastIndex
    while (left > 0) {
        if (excess[column] > 0) {
            widths[column]++
            left--
        }
        column = if (column == 0) min.lastIndex else column - 1
    }
    return widths
}

/** Where each row starts, from the top of the table, given the [heights] of the rows: one pass, kept for placing. */
internal fun tableRowOffsets(heights: IntArray): IntArray {
    val offsets = IntArray(heights.size)
    for (row in 1 until heights.size) offsets[row] = offsets[row - 1] + heights[row - 1]
    return offsets
}
