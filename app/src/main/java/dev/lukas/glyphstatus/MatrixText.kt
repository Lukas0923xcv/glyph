package dev.lukas.glyphstatus

/**
 * Renders short texts (digits, '/', ':', 'd', 'h') onto the round Glyph Matrix.
 * 13x13 uses a 3x5 font, 25x25 a 5x7 font. Digits are drawn at [BRIGHTNESS_DIGIT],
 * everything else at [BRIGHTNESS_SYMBOL].
 */
object MatrixText {

    const val BRIGHTNESS_DIGIT = 255
    const val BRIGHTNESS_SYMBOL = 128

    private val FONT_3X5 = mapOf(
        '0' to arrayOf("###", "#.#", "#.#", "#.#", "###"),
        '1' to arrayOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to arrayOf("###", "..#", "###", "#..", "###"),
        '3' to arrayOf("###", "..#", "###", "..#", "###"),
        '4' to arrayOf("#.#", "#.#", "###", "..#", "..#"),
        '5' to arrayOf("###", "#..", "###", "..#", "###"),
        '6' to arrayOf("###", "#..", "###", "#.#", "###"),
        '7' to arrayOf("###", "..#", ".#.", ".#.", ".#."),
        '8' to arrayOf("###", "#.#", "###", "#.#", "###"),
        '9' to arrayOf("###", "#.#", "###", "..#", "###"),
        '/' to arrayOf("..#", "..#", ".#.", "#..", "#.."),
        ':' to arrayOf(".", "#", ".", "#", "."),
        'd' to arrayOf("..#", "..#", "###", "#.#", "###"),
        'h' to arrayOf("#..", "#..", "###", "#.#", "#.#"),
    )

    private val FONT_5X7 = mapOf(
        '0' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        '/' to arrayOf("..#", "..#", ".#.", ".#.", ".#.", "#..", "#.."),
        ':' to arrayOf(".", ".", "#", ".", "#", ".", "."),
        'd' to arrayOf("....#", "....#", ".####", "#...#", "#...#", "#...#", ".####"),
        'h' to arrayOf("#....", "#....", "#.##.", "##..#", "#...#", "#...#", "#...#"),
    )

    /**
     * Renders the first candidate whose pixels all lie inside the visible circle.
     * Each candidate is a list of lines, stacked vertically and centered.
     * Returns null if no candidate fits.
     */
    fun render(length: Int, candidates: List<List<String>>): IntArray? {
        for (lines in candidates) {
            layout(length, lines)?.let { return it }
        }
        return null
    }

    private fun layout(length: Int, lines: List<String>): IntArray? {
        val font = if (length == 13) FONT_3X5 else FONT_5X7
        val glyphHeight = if (length == 13) 5 else 7
        val lineGap = if (length == 13) 1 else 2
        val frame = IntArray(length * length)

        val totalHeight = lines.size * glyphHeight + (lines.size - 1) * lineGap
        var y0 = (length - totalHeight) / 2
        for (line in lines) {
            val glyphs = line.map { font[it] ?: return null }
            val width = glyphs.sumOf { it[0].length } + glyphs.size - 1
            var x0 = (length - width + 1) / 2
            for ((glyph, char) in glyphs.zip(line.toList())) {
                val brightness = if (char.isDigit()) BRIGHTNESS_DIGIT else BRIGHTNESS_SYMBOL
                for ((dy, row) in glyph.withIndex()) {
                    for ((dx, c) in row.withIndex()) {
                        if (c != '#') continue
                        val x = x0 + dx
                        val y = y0 + dy
                        if (x !in 0 until length || y !in 0 until length || !MatrixIcons.isVisible(length, x, y)) {
                            return null
                        }
                        frame[y * length + x] = brightness
                    }
                }
                x0 += glyph[0].length + 1
            }
            y0 += glyphHeight + lineGap
        }
        return frame
    }
}
