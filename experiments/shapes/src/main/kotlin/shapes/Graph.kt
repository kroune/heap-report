package shapes

import shapes.models.ClassName
import java.nio.file.Path
import java.util.*
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
 * Text format (one object per line):
 *   # comment
 *   <id> <className> <shallowBytes> [refId,refId,...]
 * Ids must be dense 0..n-1. Repeated refIds are allowed and meaningful
 * (multi-edges, e.g. Object[]{x, x}).
 */
class ObjectGraph(
    val classNames: Array<ClassName>,
    val classIdx: CharArray,       // per object -> classNames index (classCount <= 65535)
    val shallow: CompactULongArray,
    val refOffsets: IntArray,     // size+1: refs of object o are refs[refOffsets[o] .. refOffsets[o+1])
    val refs: IntArray,           // flat outbound refs
    /** object count of the source heap (GRPH from a real dump), else == size. */
    val sourceObjectCount: Long = shallow.size.toLong(),
) {
    val size: Int get() = shallow.size

    fun className(index: Int): ClassName = classNames[classIdx[index].code]

    fun refCount(index: Int): Int = refOffsets[index + 1] - refOffsets[index]

    inline fun forEachRef(index: Int, block: (Int) -> Unit) {
        for (i in refOffsets[index] until refOffsets[index + 1]) block(refs[i])
    }

    fun instancesOf(className: ClassName): IntArray {
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
            sb
                .append(i)
                .append(' ')
                .append(className(i).name)
                .append(' ')
                .append(shallow[i])
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

        private val classNames = ArrayList<ClassName>()
        private val shallowSizes = CompactULongArray()
        private val referencesList = ArrayList<IntArray>()

        fun add(className: ClassName, shallow: ULong, references: IntArray = IntArray(0)): Int {
            classNames.add(className)
            shallowSizes.add(shallow)
            referencesList.add(references)
            return classNames.size - 1
        }

        fun build(): ObjectGraph {
            val classNamesSizes = classNames.size
            val names = ArrayList<ClassName>()
            val nameIdx = HashMap<ClassName, Int>()
            val classIdx = CharArray(classNamesSizes)
            val offsets = IntArray(classNamesSizes + 1)
            for (i in 0 until classNamesSizes) {
                classIdx[i] = nameIdx.getOrPut(classNames[i]) {
                    names.add(classNames[i])
                    require(names.size <= 65535) { "class table exceeds 65535 (CharArray classIdx)" }
                    names.size - 1
                }.toChar()
                offsets[i + 1] = offsets[i] + referencesList[i].size
            }
            val refs = IntArray(offsets[classNamesSizes])
            for (i in 0 until classNamesSizes) {
                referencesList[i].copyInto(refs, offsets[i])
            }
            for (i in 0 until classNamesSizes) {
                for (w in referencesList[i]) {
                    require(w in 0 until classNamesSizes) { "ref out of range: $i -> $w (size $classNamesSizes)" }
                }
            }
            shallowSizes.markImmutable()
            return ObjectGraph(
                classNames = names.toTypedArray(),
                classIdx = classIdx,
                shallow = shallowSizes,
                refOffsets = offsets,
                refs = refs
            )
        }
    }

    companion object {
        /** Direct construction from per-node arrays (tests). */
        fun of(classes: Array<ClassName>, shallow: ULongArray, refs: Array<IntArray>): ObjectGraph {
            val builder = Builder()
            for (index in classes.indices) {
                builder.add(classes[index], shallow[index], refs[index])
            }
            return builder.build()
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
            val classNames: Array<ClassName>
            val sourceN: Long
            val n: Int
            val classIdx: CharArray
            val shallow = CompactULongArray()
            val offsets: IntArray
            FastReader(path).use { reader ->
                require(reader.int() == 0x47525048) { "bad GRPH magic in $path" }
                sourceN = reader.long()
                classNames = Array(reader.int()) {
                    ClassName(reader.bytes(reader.int()).toString(Charsets.UTF_8))
                }
                require(classNames.size <= 65535) { "class table exceeds 65535 (CharArray classIdx)" }
                n = reader.int()
                // pass 1: per-node class, shallow, ref count (refs skipped cheaply)
                classIdx = CharArray(n)
                offsets = IntArray(n + 1)
                for (i in 0 until n) {
                    classIdx[i] = reader.int().toChar()
                    val v = reader.long().toULong()
                    shallow.add(v)
                    val rc = reader.int()
                    offsets[i + 1] = offsets[i] + rc
                    reader.skip(4L * rc)
                }
            }
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
            shallow.markImmutable()
            return ObjectGraph(classNames, classIdx, shallow, offsets, refs, sourceN)
        }

        fun load(path: Path): ObjectGraph {
            data class Row(val id: Int, val className: ClassName, val shallow: Long, val refs: IntArray)

            val rows = ArrayList<Row>()
            path.readLines().forEachIndexed { lineNo, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                require(parts.size >= 3) { "line ${lineNo + 1}: expected '<id> <class> <shallow> [refs]'" }
                val refs = if (parts.size >= 4) parts[3].split(',').map { it.toInt() }.toIntArray() else IntArray(0)
                rows.add(Row(parts[0].toInt(), ClassName(parts[1]), parts[2].toLong(), refs))
            }
            val builder = Builder()
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
                val row = byId[i]!!
                // TODO trace better long and convert in proper place
                builder.add(row.className, row.shallow.toULong(), row.refs)
            }
            return builder.build()
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
        val shared = b.add(ClassName("synth.SharedProps"), 64U)
        val roots = IntArray(n)
        for (i in 0 until n) {
            val libA = b.add(ClassName("synth.Lib"), 32U)
            val libB = b.add(ClassName("synth.Lib"), 32U)
            val props = b.add(ClassName("synth.Props"), 24U)
            val deps = b.add(ClassName("synth.Deps"), 48U, intArrayOf(libA, libB))
            val conf = b.add(ClassName("synth.Config"), 40U, intArrayOf(props, shared))
            roots[i] = b.add(ClassName("synth.Module"), 120U, intArrayOf(deps, conf))
        }
        return b.build() to roots
    }
}
