package shapes

import java.nio.file.Path
import java.util.BitSet
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * In-memory stand-in for the MAT object graph (SHAPES.md "Definitions"):
 * objects + outbound references. Object ids are dense, 0-based.
 *
 * Text format (one object per line):
 *   # comment
 *   <id> <className> <shallowBytes> [refId,refId,...]
 * Ids must be dense 0..n-1. Repeated refIds are allowed and meaningful
 * (multi-edges, e.g. Object[]{x, x}).
 */
class ObjectGraph(
    val classes: Array<String>,
    val shallow: LongArray,
    val refs: Array<IntArray>,
) {
    val size: Int get() = classes.size

    fun instancesOf(className: String): IntArray =
        classes.indices.filter { classes[it] == className }.toIntArray()

    fun save(path: Path) {
        val sb = StringBuilder()
        for (i in 0 until size) {
            sb.append(i).append(' ').append(classes[i]).append(' ').append(shallow[i])
            if (refs[i].isNotEmpty()) sb.append(' ').append(refs[i].joinToString(","))
            sb.append('\n')
        }
        path.writeText(sb.toString())
    }

    class Builder {
        private val cls = ArrayList<String>()
        private val sh = ArrayList<Long>()
        private val rf = ArrayList<IntArray>()

        fun add(className: String, shallow: Long, refs: IntArray = IntArray(0)): Int {
            cls.add(className); sh.add(shallow); rf.add(refs)
            return cls.size - 1
        }

        fun build(): ObjectGraph {
            val n = cls.size
            for (i in 0 until n) for (w in rf[i]) {
                require(w in 0 until n) { "ref out of range: $i -> $w (size $n)" }
            }
            return ObjectGraph(cls.toTypedArray(), sh.toLongArray(), rf.toTypedArray())
        }
    }

    companion object {
        fun load(path: Path): ObjectGraph {
            data class Row(val id: Int, val cls: String, val shallow: Long, val refs: IntArray)

            val rows = ArrayList<Row>()
            path.readLines().forEachIndexed { lineNo, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                require(parts.size >= 3) { "line ${lineNo + 1}: expected '<id> <class> <shallow> [refs]'" }
                val refs = if (parts.size >= 4) parts[3].split(',').map { it.toInt() }.toIntArray() else IntArray(0)
                rows.add(Row(parts[0].toInt(), parts[1], parts[2].toLong(), refs))
            }
            val n = rows.size
            val cls = arrayOfNulls<String>(n)
            val sh = LongArray(n)
            val rf = arrayOfNulls<IntArray>(n)
            val seen = BitSet(n)
            for (r in rows) {
                require(r.id in 0 until n) { "id ${r.id} not in dense range 0..${n - 1}" }
                require(!seen.get(r.id)) { "duplicate id ${r.id}" }
                seen.set(r.id)
                cls[r.id] = r.cls; sh[r.id] = r.shallow; rf[r.id] = r.refs
            }
            @Suppress("UNCHECKED_CAST")
            return ObjectGraph(cls as Array<String>, sh, rf as Array<IntArray>).also { g ->
                for (i in 0 until g.size) for (w in g.refs[i]) {
                    require(w in 0 until g.size) { "ref out of range: $i -> $w (size ${g.size})" }
                }
            }
        }
    }
}

/** Synthetic repetitive heaps for demos/tests (the "3000 modules" shape). */
object SynthGraphs {
    /**
     * N identical modules: module -> deps -> [lib, lib]; module -> config -> [props, shared].
     * One SharedProps object referenced by all configs (in-degree N) to exercise sharing.
     */
    fun modules(n: Int): Pair<ObjectGraph, IntArray> {
        val b = ObjectGraph.Builder()
        val shared = b.add("synth.SharedProps", 64)
        val roots = IntArray(n)
        for (i in 0 until n) {
            val libA = b.add("synth.Lib", 32)
            val libB = b.add("synth.Lib", 32)
            val props = b.add("synth.Props", 24)
            val deps = b.add("synth.Deps", 48, intArrayOf(libA, libB))
            val conf = b.add("synth.Config", 40, intArrayOf(props, shared))
            roots[i] = b.add("synth.Module", 120, intArrayOf(deps, conf))
        }
        return b.build() to roots
    }
}
