package shapes.cli

import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import shapes.hprof.HprofDirectory
import shapes.hprof.HprofFile
import shapes.hprof.HprofHeapIndex
import shapes.hprof.HprofSubTag
import shapes.hprof.mapChunksParallel
import shapes.hprof.skipSubRecord

/** Validates the standalone HPROF reader against a dump: counts and layouts. */
internal class HprofScanCommand : ShapesCommand(
    "hprof-scan",
    "Walk an HPROF dump with the standalone reader and print validation counts",
) {
    private val hprof by option("--hprof", help = "Heap dump file")
        .path(mustExist = true, canBeDir = false)
        .required()

    override fun run() {
        val startedAt = System.nanoTime()
        HprofFile.open(hprof).use { file ->
            println("identifierSize=${file.identifierSize} size=${file.size}")

            val directory = HprofDirectory.scan(file)
            println(
                "directory: ${directory.stringCount()} strings, " +
                    "${directory.classNameStringIds.size} loaded classes, " +
                    "${directory.heapSegments.size} heap segments " +
                    "in ${elapsedMs(startedAt)} ms"
            )

            val indexAt = System.nanoTime()
            val heapIndex = HprofHeapIndex.build(file, directory)
            val counts = heapIndex.counts
            println(
                "heap index: ${heapIndex.classStore.size} class dumps, " +
                    "${counts.instances} instances, ${counts.objectArrays} object arrays, " +
                    "${counts.primitiveArrays} primitive arrays " +
                    "(${counts.objects} objects, ${heapIndex.chunks.size} chunks, " +
                    "${heapIndex.rootAddresses.size} root refs) " +
                    "in ${elapsedMs(indexAt)} ms"
            )

            val classStore = heapIndex.classStore
            val javaLangClassIndices = classStore.requireByName("java.lang.Class").toHashSet()
            println(
                "java.lang.Class ids=${javaLangClassIndices.size}; sample names: " +
                    (0 until minOf(3, classStore.size)).joinToString { classStore[it].name }
            )

            val refsAt = System.nanoTime()
            class ChunkCounters {
                var instanceRefs = 0L
                var arrayRefs = 0L
                var nullInstanceRefs = 0L
                var nullArrayRefs = 0L
                var classInstances = 0L
                var unknownClassRefs = 0L
                var minAddress = Long.MAX_VALUE
                var maxAddress = Long.MIN_VALUE
            }

            val identifierSize = file.identifierSize
            val sharedClassInstances = java.util.concurrent.atomic.AtomicLongArray(classStore.size)
            val chunkCounters = mapChunksParallel(heapIndex.chunks) { _, chunk ->
                val counters = ChunkCounters()
                val localClassInstances = IntArray(classStore.size)
                chunk.forEachRange { start, end ->
                    var offset = start
                    while (offset < end) {
                        val tag = file.u1(offset)
                        offset++
                        when (tag) {
                            HprofSubTag.INSTANCE_DUMP -> {
                                val address = file.id(offset)
                                if (address < counters.minAddress) counters.minAddress = address
                                if (address > counters.maxAddress) counters.maxAddress = address
                                val classAddress = file.id(offset + identifierSize + 4)
                                val dataStart = offset + identifierSize + 4 + identifierSize + 4
                                val dataBytes = file.u4(offset + identifierSize + 4 + identifierSize)
                                val classIndex = classStore.indexOf(classAddress)
                                if (classIndex < 0) {
                                    counters.unknownClassRefs++
                                } else {
                                    localClassInstances[classIndex]++
                                    if (classIndex in javaLangClassIndices) counters.classInstances++
                                    for (fieldOffset in classStore[classIndex].referenceFieldOffsets) {
                                        if (file.id(dataStart + fieldOffset) != 0L) {
                                            counters.instanceRefs++
                                        } else {
                                            counters.nullInstanceRefs++
                                        }
                                    }
                                }
                                offset = dataStart + dataBytes
                            }
                            HprofSubTag.OBJECT_ARRAY_DUMP -> {
                                val address = file.id(offset)
                                if (address < counters.minAddress) counters.minAddress = address
                                if (address > counters.maxAddress) counters.maxAddress = address
                                val count = file.u4(offset + identifierSize + 4)
                                val elementsStart = offset + identifierSize + 4 + 4 + identifierSize
                                for (elementIndex in 0 until count) {
                                    if (file.id(elementsStart + elementIndex * identifierSize) != 0L) {
                                        counters.arrayRefs++
                                    } else {
                                        counters.nullArrayRefs++
                                    }
                                }
                                offset = elementsStart + count * identifierSize
                            }
                            HprofSubTag.PRIMITIVE_ARRAY_DUMP -> {
                                val address = file.id(offset)
                                if (address < counters.minAddress) counters.minAddress = address
                                if (address > counters.maxAddress) counters.maxAddress = address
                                offset = skipSubRecord(file, tag, offset)
                            }
                            else -> offset = skipSubRecord(file, tag, offset)
                        }
                    }
                }
                for (classIndex in 0 until classStore.size) {
                    val instanceCount = localClassInstances[classIndex]
                    if (instanceCount != 0) sharedClassInstances.addAndGet(classIndex, instanceCount.toLong())
                }
                counters
            }

            var instanceRefs = 0L
            var arrayRefs = 0L
            var nullRefs = 0L
            var classInstances = 0L
            var unknownClassRefs = 0L
            var minAddress = Long.MAX_VALUE
            var maxAddress = Long.MIN_VALUE
            for (counters in chunkCounters) {
                instanceRefs += counters.instanceRefs
                arrayRefs += counters.arrayRefs
                nullRefs += counters.nullInstanceRefs + counters.nullArrayRefs
                classInstances += counters.classInstances
                unknownClassRefs += counters.unknownClassRefs
                minAddress = minOf(minAddress, counters.minAddress)
                maxAddress = maxOf(maxAddress, counters.maxAddress)
            }
            var usedClasses = 0
            for (classIndex in 0 until classStore.size) {
                if (sharedClassInstances.get(classIndex) > 0) usedClasses++
            }
            println(
                "refs: $instanceRefs instance + $arrayRefs array non-null " +
                    "(total ${instanceRefs + arrayRefs}), $nullRefs null slots " +
                    "in ${elapsedMs(refsAt)} ms"
            )
            println(
                "objects: $classInstances java.lang.Class instances, " +
                    "$unknownClassRefs unknown-class instances, $usedClasses used classes, " +
                    "addresses $minAddress..$maxAddress " +
                    "(span ${(maxAddress - minAddress) / (1 shl 30)} GiB)"
            )
            println("total: ${elapsedMs(startedAt)} ms")
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}
