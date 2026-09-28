package com.teamsassignments.widget.data

/**
 * Gives each class a stripe colour that stays the same across syncs.
 *
 * A plain hash of the class name would give ~9 classes only 8 colours with several clashes,
 * so colours are assigned once and remembered in [WidgetState.classColors]. A new class takes
 * the colour least used by the classes currently on the list, preferring its hash slot.
 */
object ClassColors {
    /** Mid-tone ARGB colours that read well as a stripe on both light and dark surfaces. */
    val PALETTE: List<Long> = listOf(
        0xFF4F86F7, // blue
        0xFF1FA89A, // teal
        0xFF5DB85F, // green
        0xFFE8A317, // amber
        0xFFF2724B, // coral
        0xFFE5484D, // red
        0xFFD6559C, // pink
        0xFF9A6AF2, // purple
    )

    private const val MAX_REMEMBERED = 64

    fun assign(existing: Map<String, Int>, classNames: Collection<String>): Map<String, Int> {
        val present = classNames.toSortedSet()
        val result = LinkedHashMap<String, Int>()
        existing.forEach { (name, index) -> if (index in PALETTE.indices) result[name] = index }

        val usage = IntArray(PALETTE.size)
        present.forEach { name -> result[name]?.let { usage[it]++ } }

        for (name in present) {
            if (name in result) continue
            val preferred = preferredIndex(name)
            // Least-used colour; ties go to the first one at or after the preferred slot.
            val index = PALETTE.indices
                .map { (preferred + it) % PALETTE.size }
                .minBy { usage[it] }
            result[name] = index
            usage[index]++
        }

        if (result.size > MAX_REMEMBERED) {
            result.keys.filter { it !in present }.take(result.size - MAX_REMEMBERED).forEach(result::remove)
        }
        return result
    }

    fun colorFor(className: String, assigned: Map<String, Int>): Long =
        PALETTE[assigned[className] ?: preferredIndex(className)]

    private fun preferredIndex(className: String): Int = Math.floorMod(className.hashCode(), PALETTE.size)
}
