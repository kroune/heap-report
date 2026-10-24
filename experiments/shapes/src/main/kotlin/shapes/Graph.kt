package shapes

import java.nio.file.Path
import java.util.BitSet
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Compact object graph used by the analyzer (SHAPES.md "Definitions"):
 * objects + outbound references. Object ids are dense, 0-based.
 *
 * CSR storage (flat refs + offsets, class table + per-object index): at
 * 10M+ objects, per-node IntArray shells cost more than the refs themselves
 * (measured on real heaps — see README). Hot loops use [forEachRef].
 *
 * Heap footprint per object (JFR-driven, see README):
 *   classIdx 2 B (CharArray — an extracted scope never has 65k classes),
 *   shallow  4 B (IntArray; the rare >2 GB single object — a giant array —
 *   is encoded as a negative index into [shallowBig]),
 *   offsets  4 B, refs 4 B/ref.
 *
 * Text format (one object per line):
 *   # comment
 *   <id> <className> <shallowBytes> [refId,refId,...]
 * Ids must be dense 0..n-1. Repeated refIds are allowed and meaningful
 * (multi-edges, e.g. Object[]{x, x}).
 */
class ObjectGraph(
    val classNames: Array<String>,
    val classIdx: CharArray,       // per object -> classNames index (classCount <= 65535)
    private val shallowI: IntArray, // per object: >= 0 -> shallow bytes, < 0 -> ~index into shallowBig
    private val shallowBig: LongArray, // overflow for objects >= 2^31 bytes
    val refOffsets: IntArray,     // size+1: refs of object o are refs[refOffsets[o] .. refOffsets[o+1])
    val refs: IntArray,           // flat outbound refs
    /** object count of the source heap (GRPH from a real dump), else == size. */
    val sourceObjectCount: Long = shallowI.size.toLong(),
) {
    val size: Int get() = shallowI.size

    fun className(o: Int): String = classNames[classIdx[o].code]

    /** shallow bytes of object o (Long: a single array CAN exceed 2^31 bytes). */
    fun shallowOf(o: Int): Long {
        val v = shallowI[o]
        return if (v >= 0) v.toLong() else shallowBig[-v - 1]
    }

    fun refCount(o: Int): Int = refOffsets[o + 1] - refOffsets[o]

    inline fun forEachRef(o: Int, block: (Int) -> Unit) {
        for (i in refOffsets[o] until refOffsets[o + 1]) block(refs[i])
    }

    fun instancesOf(className: String): IntArray {
        val idx = classNames.indexOf(className)
        if (idx < 0) return IntArray(0)
        var count = 0
        for (i in classIdx.indices) if (classIdx[i].code == idx) count++
        val out = IntArray(count)
        var p = 0
        for (i in classIdx.indices) if (classIdx[i].code == idx) out[p++] = i
        return out
    }

    fun save(path: Path) {
        val sb = StringBuilder()
        for (i in 0 until size) {
            sb.append(i).append(' ').append(className(i)).append(' ').append(shallowOf(i))
            if (refCount(i) > 0) {
                sb.append(' ')
                for (j in refOffsets[i] until refOffsets[i + 1]) {
                    if (j > refOffsets[i]) sb.append(',')
                    sb.append(refs[j])
                }
            }
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
            val names = ArrayList<String>()
            val nameIdx = HashMap<String, Int>()
            val classIdx = CharArray(n)
            val offsets = IntArray(n + 1)
            for (i in 0 until n) {
                classIdx[i] = nameIdx.getOrPut(cls[i]) {
                    names.add(cls[i])
                    require(names.size <= 65535) { "class table exceeds 65535 (CharArray classIdx)" }
                    names.size - 1
                }.toChar()
                offsets[i + 1] = offsets[i] + rf[i].size
            }
            val refs = IntArray(offsets[n])
            for (i in 0 until n) rf[i].copyInto(refs, offsets[i])
            for (i in 0 until n) for (w in rf[i]) {
                require(w in 0 until n) { "ref out of range: $i -> $w (size $n)" }
            }
            val (shI, shBig) = packShallow(sh.toLongArray())
            return ObjectGraph(names.toTypedArray(), classIdx, shI, shBig, offsets, refs)
        }
    }

    companion object {
        /** Pack shallow sizes: int-direct + a small overflow table for the
         * rare single object >= 2^31 bytes (a giant array). */
        private fun packShallow(values: LongArray): Pair<IntArray, LongArray> {
            val packed = IntArray(values.size)
            var bigs = LongArray(0)
            var bigCount = 0
            for (i in values.indices) {
                val v = values[i]
                if (v in 0..Int.MAX_VALUE) {
                    packed[i] = v.toInt()
                } else {
                    if (bigCount == bigs.size) bigs = bigs.copyOf(maxOf(8, bigs.size * 2))
                    packed[i] = -(bigCount + 1)
                    bigs[bigCount++] = v
                }
            }
            return packed to bigs.copyOf(bigCount)
        }
        /** Direct construction from per-node arrays (tests). */
        fun of(classes: Array<String>, shallow: LongArray, refs: Array<IntArray>): ObjectGraph {
            val b = Builder()
            for (i in classes.indices) b.add(classes[i], shallow[i], refs[i])
            return b.build()
        }

        /** Bulk big-endian reader: DataInputStream per-call overhead dominated load time (JFR). */
        private class FastReader(path: Path) : AutoCloseable {
            private val inp = java.nio.file.Files.newInputStream(path)
            private var buf = ByteArray(1 shl 20)
            private var pos = 0
            private var lim = 0

            private fun need(n: Int) {
                if (lim - pos < n) {
                    // compact + refill; n never exceeds buf.size in this format
                    System.arraycopy(buf, pos, buf, 0, lim - pos)
                    lim -= pos
                    pos = 0
                    while (lim < n) {
                        val r = inp.read(buf, lim, buf.size - lim)
                        if (r < 0) throw java.io.EOFException()
                        lim += r
                    }
                }
            }

            fun int(): Int {
                need(4)
                val r = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
                        ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
                pos += 4
                return r
            }

            fun long(): Long {
                need(8)
                var r = 0L
                for (i in 0 until 8) r = (r shl 8) or (buf[pos + i].toLong() and 0xFF)
                pos += 8
                return r
            }

            fun bytes(n: Int): ByteArray {
                need(n)
                val r = buf.copyOfRange(pos, pos + n)
                pos += n
                return r
            }

            fun skip(n: Long) {
                var left = n
                val avail = (lim - pos).toLong()
                if (avail >= left) {
                    pos += left.toInt(); return
                }
                pos = lim; left -= avail
                while (left > 0) {
                    val s = inp.skip(left)
                    if (s <= 0) throw java.io.EOFException()
                    left -= s
                }
            }

            override fun close() = inp.close()
        }

        /** GRPH binary, written by shapes.mat.MatExtract (dense ids, refs remapped). */
        fun loadGrph(path: Path): ObjectGraph {
            val classNames: Array<String>
            val sourceN: Long
            val n: Int
            val classIdx: CharArray
            val shI: IntArray
            var shBig = LongArray(0)
            var bigCount = 0
            val offsets: IntArray
            FastReader(path).use { d ->
                require(d.int() == 0x47525048) { "bad GRPH magic in $path" }
                sourceN = d.long()
                classNames = Array(d.int()) { d.bytes(d.int()).toString(Charsets.UTF_8) }
                require(classNames.size <= 65535) { "class table exceeds 65535 (CharArray classIdx)" }
                n = d.int()
                // pass 1: per-node class, shallow, ref count (refs skipped cheaply)
                classIdx = CharArray(n)
                shI = IntArray(n)
                offsets = IntArray(n + 1)
                for (i in 0 until n) {
                    classIdx[i] = d.int().toChar()
                    val v = d.long()
                    if (v in 0..Int.MAX_VALUE) {
                        shI[i] = v.toInt()
                    } else {   // a single object >= 2^31 bytes: overflow table
                        if (bigCount == shBig.size) shBig = shBig.copyOf(maxOf(8, shBig.size * 2))
                        shI[i] = -(bigCount + 1)
                        shBig[bigCount++] = v
                    }
                    val rc = d.int()
                    offsets[i + 1] = offsets[i] + rc
                    d.skip(4L * rc)
                }
            }
            shBig = shBig.copyOf(bigCount)
            // pass 2: refs into the flat array (file is in page cache)
            val refs = IntArray(offsets[n])
            FastReader(path).use { d ->
                require(d.int() == 0x47525048) { "bad GRPH magic in $path" }
                d.long()                                    // sourceN
                repeat(d.int()) { d.skip(d.int().toLong()) } // class table
                require(d.int() == n) { "GRPH changed between passes" }
                for (i in 0 until n) {
                    d.skip(12L) // classIdx + shallow
                    val rc = d.int()
                    val base = offsets[i]
                    for (j in 0 until rc) refs[base + j] = d.int()
                }
            }
            return ObjectGraph(classNames, classIdx, shI, shBig, offsets, refs, sourceN)
        }

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
            val b = Builder()
            val n = rows.size
            val seen = BitSet(n)
            val byId = arrayOfNulls<Row>(n)
            for (r in rows) {
                require(r.id in 0 until n) { "id ${r.id} not in dense range 0..${n - 1}" }
                require(!seen.get(r.id)) { "duplicate id ${r.id}" }
                seen.set(r.id)
                byId[r.id] = r
            }
            for (i in 0 until n) {
                val r = byId[i]!!
                b.add(r.cls, r.shallow, r.refs)
            }
            return b.build()
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
