package dev.lukas.glyphstatus

/**
 * Pixel icons for the round Glyph Matrix (the DOWN cross). '#' = LED on, '.' = LED off.
 * Every lit pixel lies inside the visible circle (see [isVisible]).
 */
object MatrixIcons {

    const val BRIGHTNESS_DOWN = 255

    private val CROSS_25 = arrayOf(
        ".........................",
        ".........................",
        ".........................",
        ".........................",
        ".........................",
        ".........................",
        ".....###.........###.....",
        "......###.......###......",
        ".......###.....###.......",
        "........###...###........",
        ".........###.###.........",
        "..........#####..........",
        "...........###...........",
        "..........#####..........",
        ".........###.###.........",
        "........###...###........",
        ".......###.....###.......",
        "......###.......###......",
        ".....###.........###.....",
        ".........................",
        ".........................",
        ".........................",
        ".........................",
        ".........................",
        ".........................",
    )

    private val CROSS_13 = arrayOf(
        ".............",
        ".............",
        ".............",
        "...#.....#...",
        "....#...#....",
        ".....#.#.....",
        "......#......",
        ".....#.#.....",
        "....#...#....",
        "...#.....#...",
        ".............",
        ".............",
        ".............",
    )

    /** Visible column range per row of the Phone (4a) Pro matrix, from the SDK's LED allocation chart. */
    private val VISIBLE_13 = arrayOf(
        4..8, 2..10, 1..11, 1..11,
        0..12, 0..12, 0..12, 0..12, 0..12,
        1..11, 1..11, 2..10, 4..8,
    )

    fun cross(length: Int): Array<String> = if (length == 13) CROSS_13 else CROSS_25

    /** Whether the LED at (x, y) physically exists on the round matrix. */
    fun isVisible(length: Int, x: Int, y: Int): Boolean {
        if (length == 13) return x in VISIBLE_13[y]
        val center = (length - 1) / 2.0
        val dx = x - center
        val dy = y - center
        return dx * dx + dy * dy <= (length / 2.0) * (length / 2.0)
    }

    /** Converts an icon into the row-major brightness array expected by setMatrixFrame(int[]). */
    fun toFrame(icon: Array<String>, brightness: Int): IntArray {
        val length = icon.size
        return IntArray(length * length) { i ->
            if (icon[i / length][i % length] == '#') brightness else 0
        }
    }
}
