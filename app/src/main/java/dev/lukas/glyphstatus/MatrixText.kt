package dev.lukas.glyphstatus

/**
 * Renders short texts onto the round Glyph Matrix: digits, A–Z (lowercase falls back to
 * uppercase; 'd' and 'h' have their own glyphs), the symbols / : . - + % ° ! ? and space,
 * and the icons ✓ ✗. 13x13 uses a 3x5 font, 25x25 a 5x7 font.
 * Digits and icons are drawn at [BRIGHTNESS_DIGIT], everything else at [BRIGHTNESS_SYMBOL].
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
        'C' to arrayOf("###", "#..", "#..", "#..", "###"),
        'R' to arrayOf("##.", "#.#", "##.", "#.#", "#.#"),
        'D' to arrayOf("##.", "#.#", "#.#", "#.#", "##."),
        'L' to arrayOf("#..", "#..", "#..", "#..", "###"),
        // 3 px wide is too narrow for a readable 'N'; a lowercase-style 'n' reads clearly.
        'N' to arrayOf("##.", "#.#", "#.#", "#.#", "#.#"),
        'B' to arrayOf("##.", "#.#", "##.", "#.#", "##."),
        '.' to arrayOf(".", ".", ".", ".", "#"),
        '✓' to arrayOf("...", "..#", "..#", "#.#", ".#."),
        '✗' to arrayOf("...", "#.#", ".#.", "#.#", "..."),
        'H' to arrayOf("#.#", "#.#", "###", "#.#", "#.#"),
        'Q' to arrayOf(".#.", "#.#", "#.#", "##.", ".##"),
        'U' to arrayOf("#.#", "#.#", "#.#", "#.#", "###"),
        'V' to arrayOf("#.#", "#.#", "#.#", "#.#", ".#."),
        'P' to arrayOf("##.", "#.#", "##.", "#..", "#.."),
        'A' to arrayOf(".#.", "#.#", "###", "#.#", "#.#"),
        'E' to arrayOf("###", "#..", "##.", "#..", "###"),
        'F' to arrayOf("###", "#..", "##.", "#..", "#.."),
        'G' to arrayOf(".##", "#..", "#.#", "#.#", ".##"),
        'I' to arrayOf("###", ".#.", ".#.", ".#.", "###"),
        'J' to arrayOf("..#", "..#", "..#", "#.#", ".#."),
        'K' to arrayOf("#.#", "#.#", "##.", "#.#", "#.#"),
        'M' to arrayOf("#.#", "###", "###", "#.#", "#.#"),
        'O' to arrayOf(".#.", "#.#", "#.#", "#.#", ".#."),
        'S' to arrayOf(".##", "#..", ".#.", "..#", "##."),
        'T' to arrayOf("###", ".#.", ".#.", ".#.", ".#."),
        'W' to arrayOf("#.#", "#.#", "###", "###", "#.#"),
        'X' to arrayOf("#.#", "#.#", ".#.", "#.#", "#.#"),
        'Y' to arrayOf("#.#", "#.#", ".#.", ".#.", ".#."),
        'Z' to arrayOf("###", "..#", ".#.", "#..", "###"),
        '-' to arrayOf("...", "...", "###", "...", "..."),
        '+' to arrayOf("...", ".#.", "###", ".#.", "..."),
        '%' to arrayOf("#.#", "..#", ".#.", "#..", "#.#"),
        '°' to arrayOf(".#.", "#.#", ".#.", "...", "..."),
        '!' to arrayOf("#", "#", "#", ".", "#"),
        '?' to arrayOf("##.", "..#", ".#.", "...", ".#."),
        ' ' to arrayOf(".", ".", ".", ".", "."),
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
        'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'D' to arrayOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'N' to arrayOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        '.' to arrayOf(".", ".", ".", ".", ".", ".", "#"),
        '✓' to arrayOf(".....", "....#", "....#", "...#.", "#.#..", ".#...", "....."),
        '✗' to arrayOf(".....", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "....."),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
        'I' to arrayOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '-' to arrayOf("...", "...", "...", "###", "...", "...", "..."),
        '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        '%' to arrayOf("##...", "##..#", "...#.", "..#..", ".#...", "#..##", "...##"),
        '°' to arrayOf(".#.", "#.#", ".#.", "...", "...", "...", "..."),
        '!' to arrayOf("#", "#", "#", "#", "#", ".", "#"),
        '?' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
        ' ' to arrayOf("..", "..", "..", "..", "..", "..", ".."),
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

    /**
     * [top] over [bottom], separated by a dimmed fraction bar ("12" over "15").
     * Tries with a blank row around the bar first, then without. Returns null if it does not fit.
     */
    fun renderFraction(length: Int, top: String, bottom: String): IntArray? {
        for (gap in listOf(1, 0)) {
            layoutFraction(length, top, bottom, gap)?.let { return it }
        }
        return null
    }

    /** One line of text, horizontally centered, starting at row [y0]. Null if it does not fit. */
    fun renderLineAt(length: Int, line: String, y0: Int): IntArray? {
        val frame = IntArray(length * length)
        return if (drawLine(frame, length, line, y0)) frame else null
    }

    fun glyphHeight(length: Int) = if (length == 13) 5 else 7

    private fun layout(length: Int, lines: List<String>): IntArray? {
        val glyphHeight = glyphHeight(length)
        val lineGap = if (length == 13) 1 else 2
        val frame = IntArray(length * length)

        val totalHeight = lines.size * glyphHeight + (lines.size - 1) * lineGap
        var y0 = (length - totalHeight) / 2
        for (line in lines) {
            if (!drawLine(frame, length, line, y0)) return null
            y0 += glyphHeight + lineGap
        }
        return frame
    }

    private fun layoutFraction(length: Int, top: String, bottom: String, gap: Int): IntArray? {
        val glyphHeight = glyphHeight(length)
        val frame = IntArray(length * length)
        val totalHeight = 2 * glyphHeight + 2 * gap + 1
        val y0 = (length - totalHeight) / 2
        if (!drawLine(frame, length, top, y0)) return null
        val barY = y0 + glyphHeight + gap
        val barWidth = maxOf(width(length, top) ?: return null, width(length, bottom) ?: return null) + 2
        val barX = (length - barWidth + 1) / 2
        for (x in barX until barX + barWidth) {
            if (!set(frame, length, x, barY, BRIGHTNESS_SYMBOL)) return null
        }
        if (!drawLine(frame, length, bottom, barY + 1 + gap)) return null
        return frame
    }

    private fun font(length: Int) = if (length == 13) FONT_3X5 else FONT_5X7

    /** The glyph for [char], falling back to its uppercase form; null if the font has neither. */
    fun glyph(length: Int, char: Char): Array<String>? = font(length).let { it[char] ?: it[char.uppercaseChar()] }

    private fun width(length: Int, line: String): Int? {
        val glyphs = line.map { glyph(length, it) ?: return null }
        return glyphs.sumOf { it[0].length } + glyphs.size - 1
    }

    /** Draws [line] horizontally centered at row [y0]; false if a glyph is unknown or leaves the circle. */
    private fun drawLine(frame: IntArray, length: Int, line: String, y0: Int): Boolean {
        var x0 = (length - (width(length, line) ?: return false) + 1) / 2
        for (char in line) {
            val glyph = glyph(length, char) ?: return false
            val brightness = if (char.isDigit() || char == '✓' || char == '✗') BRIGHTNESS_DIGIT else BRIGHTNESS_SYMBOL
            for ((dy, row) in glyph.withIndex()) {
                for ((dx, c) in row.withIndex()) {
                    if (c == '#' && !set(frame, length, x0 + dx, y0 + dy, brightness)) return false
                }
            }
            x0 += glyph[0].length + 1
        }
        return true
    }

    private fun set(frame: IntArray, length: Int, x: Int, y: Int, brightness: Int): Boolean {
        if (x !in 0 until length || y !in 0 until length || !MatrixIcons.isVisible(length, x, y)) return false
        frame[y * length + x] = brightness
        return true
    }
}
