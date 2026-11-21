package shapes

/** Progress sink: one line per phase or notable event. */
typealias Log = (String) -> Unit

/**
 * Runs [block] as a named phase and logs its wall time; [summary] describes
 * the result on the same line ("name: summary in N ms").
 */
inline fun <T> Log.phase(name: String, summary: (T) -> String = { "" }, block: () -> T): T {
    val startedAt = System.nanoTime()
    val value = block()
    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
    val text = summary(value)
    this(if (text.isEmpty()) "$name: $elapsedMillis ms" else "$name: $text in $elapsedMillis ms")
    return value
}
