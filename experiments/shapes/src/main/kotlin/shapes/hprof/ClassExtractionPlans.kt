package shapes.hprof

import shapes.graph.ClassName
import shapes.relaxation.FieldConcealmentRules

/** How one class's instances contribute edges to the structural graph. */
internal enum class PlanKind {
    /** All reference fields become edges; targets deduplicate per object. */
    PLAIN,

    /**
     * Named technical edges removed with alias protection, output
     * deduplicated (hash-table entry nodes, tree nodes, copy chains).
     */
    DROP_EDGES,

    /**
     * Only named fields kept, deduplicated; when every kept field is null the
     * original reference list passes through unchanged, replicating
     * `StructuralGraphProjector.keepNamedTargets` (ConcurrentHashMap,
     * RegularImmutableMap, DefaultImmutableAttributesContainer).
     */
    KEEP_ONLY,

    /** View objects: no edges at all (map key-set/values/entry-set views). */
    NO_EDGES,

    /** Plain object array: non-null elements, deduplicated per object. */
    OBJECT_ARRAY,

    /** Entry arrays flattened to a distinct element set. */
    ENTRY_ARRAY_SET,

    /** Hash-table backbone: entry-node elements expand to full bucket chains. */
    NODE_TABLE,

    /** Primitive arrays: always leaves. */
    PRIMITIVE_ARRAY,
}

/** The per-node link table stores this many chain-link slots. */
internal const val MAX_LINK_FIELDS = 3

/**
 * Precomputed extraction policy for one HPROF class. Offsets are byte offsets
 * of reference fields within the serialized instance data.
 */
internal class ClassExtractionPlan(
    val kind: PlanKind,
    /** Projected class name for the output graph. */
    val graphClassName: String,
    /** Modeled shallow size of one instance (not used for arrays). */
    val shallowSize: Long,
    /** Ref-field offsets emitted as structural edges (dropped names excluded). */
    val readOffsets: IntArray,
    /** Technical edges removed by projection (alias-protected). */
    val dropOffsets: IntArray,
    /** Kept named fields for [PlanKind.KEEP_ONLY]. */
    val keepOffsets: IntArray,
    /** Reference-mechanics fields (referent/queue/next/discovered). */
    val mechanicsOffsets: IntArray,
    /** Fields concealed by relaxation rules. */
    val concealedOffsets: IntArray,
    /** This class is a chain node reachable from a container's backbone. */
    val isEntryNode: Boolean,
    /** Offsets of chain links (next/before/after/left/right/parent) for entry nodes. */
    val linkOffsets: IntArray,
    /** Fields whose targets seed chain expansion of this instance (container backbones). */
    val expandOffsets: IntArray,
    /** Chain expansion walks through this node but conceals it from the member set. */
    val isTransparentEntryNode: Boolean,
    /** Instances are java.lang.Class objects excluded from the graph. */
    val isExcludedClassObject: Boolean,
) {
    /** Assigned graph class-table index, set on first use (CAS). */
    @Volatile
    var graphClassIndex: Int = -1

    val isArrayKind: Boolean
        get() = kind == PlanKind.PRIMITIVE_ARRAY || kind == PlanKind.OBJECT_ARRAY ||
            kind == PlanKind.NODE_TABLE || kind == PlanKind.ENTRY_ARRAY_SET
}

/** Builds extraction plans for every class in the store. */
internal object ClassExtractionPlans {
    private const val HASH_MAP_NODE = "java.util.HashMap\$Node"
    private const val CONCURRENT_HASH_MAP = "java.util.concurrent.ConcurrentHashMap"
    private const val CONCURRENT_HASH_MAP_NODE = "$CONCURRENT_HASH_MAP\$Node"
    private const val IMMUTABLE_MAP_ENTRY = "com.google.common.collect.ImmutableMapEntry"
    private const val REGULAR_IMMUTABLE_MAP = "com.google.common.collect.RegularImmutableMap"
    private const val JAVA_LANG_CLASS = "java.lang.Class"
    private const val JAVA_LANG_REF_REFERENCE = "java.lang.ref.Reference"

    private val REFERENCE_MECHANICS_FIELDS = setOf("referent", "queue", "next", "discovered")

    /**
     * Built-in normalization of one class, resolved to field offsets per dump.
     * [links] must be a subset of [dropped]; [expand] names seed assembly-time
     * chain expansion and never become direct edges.
     */
    private class Normalization(
        val kind: PlanKind = PlanKind.DROP_EDGES,
        val dropped: Set<String> = emptySet(),
        val kept: Set<String> = emptySet(),
        val links: Set<String> = emptySet(),
        val expand: Set<String> = emptySet(),
        /** Projected class name for the output graph; null keeps the source name. */
        val projectedName: String? = null,
        /** Chain expansion walks through this node without emitting it as a member. */
        val transparent: Boolean = false,
    )

    /**
     * Exact-name rules. Entry-node classes are JDK/library internals with
     * closed hierarchies, so exact names are complete; every known subclass
     * (TreeNode, LinkedHashMap\$Entry, the CHM bin nodes) has its own row.
     * Tree topology (left/right/parent) is balance/insertion-order state, and
     * bucket/order links are backbone plumbing — neither is content.
     */
    private val EXACT_RULES: Map<String, Normalization> = buildMap {
        // Hash-table entry nodes: chain/order links drop; key and value stay.
        put(HASH_MAP_NODE, Normalization(dropped = setOf("next"), links = setOf("next")))
        put(
            "java.util.LinkedHashMap\$Entry",
            Normalization(
                dropped = setOf("next", "before", "after"),
                links = setOf("next", "before", "after"),
            ),
        )
        put(
            "java.util.HashMap\$TreeNode",
            Normalization(
                dropped = setOf("next", "before", "after", "parent", "left", "right", "prev"),
                links = setOf("next", "prev", "parent"),
                projectedName = HASH_MAP_NODE,
            ),
        )
        put(
            CONCURRENT_HASH_MAP_NODE,
            Normalization(dropped = setOf("next"), links = setOf("next")),
        )
        put(
            "$CONCURRENT_HASH_MAP\$TreeNode",
            Normalization(
                dropped = setOf("next", "prev", "parent", "left", "right"),
                links = setOf("next", "prev", "parent"),
                projectedName = CONCURRENT_HASH_MAP_NODE,
            ),
        )
        // Bin containers are treeification/resize state, not content: the
        // expansion walks through them and conceals them from the member set.
        put(
            "$CONCURRENT_HASH_MAP\$TreeBin",
            Normalization(
                dropped = setOf("root", "first", "waiter", "next", "key", "val"),
                links = setOf("first", "next"),
                transparent = true,
            ),
        )
        put(
            "$CONCURRENT_HASH_MAP\$ForwardingNode",
            Normalization(dropped = setOf("nextTable", "next", "key", "val"), transparent = true),
        )
        put(
            "$CONCURRENT_HASH_MAP\$ReservationNode",
            Normalization(dropped = setOf("next", "key", "val"), transparent = true),
        )
        put("java.util.Hashtable\$Entry", Normalization(dropped = setOf("next"), links = setOf("next")))
        // WeakHashMap entries subclass WeakReference: the FIRST `next` is the
        // entry's own bucket link; Reference.next stays GC mechanics.
        put("java.util.WeakHashMap\$Entry", Normalization(dropped = setOf("next"), links = setOf("next")))
        put(
            "java.util.TreeMap\$Entry",
            Normalization(
                dropped = setOf("left", "right", "parent"),
                links = setOf("left", "right", "parent"),
            ),
        )
        put(
            "java.util.LinkedList\$Node",
            Normalization(dropped = setOf("prev", "next"), links = setOf("prev", "next")),
        )
        put(
            "java.util.concurrent.ConcurrentLinkedQueue\$Node",
            Normalization(dropped = setOf("next"), links = setOf("next")),
        )
        // Groovy's per-metaclass method index: two interleaved chains over the
        // same table entries (hash buckets and per-class lists).
        put(
            "org.codehaus.groovy.runtime.metaclass.MetaMethodIndex\$Entry",
            Normalization(
                dropped = setOf("nextHashEntry", "nextClassEntry"),
                links = setOf("nextHashEntry", "nextClassEntry"),
            ),
        )
        // Guava cache LRU rings: access/write order is a doubly-linked ring
        // through a sentinel, so every fill level is a distinct SCC. The ring
        // links drop and the queues expand to their member set; hash `next`
        // stays (it is the reachability path for non-ringed entries).
        val guavaCache = "com.google.common.cache.LocalCache"
        put("$guavaCache\$AccessQueue", Normalization(expand = setOf("head")))
        put("$guavaCache\$WriteQueue", Normalization(expand = setOf("head")))
        put(
            "$guavaCache\$AccessQueue\$1",
            Normalization(
                dropped = setOf("nextAccess", "previousAccess"),
                links = setOf("nextAccess", "previousAccess"),
            ),
        )
        put(
            "$guavaCache\$WriteQueue\$1",
            Normalization(
                dropped = setOf("nextWrite", "previousWrite"),
                links = setOf("nextWrite", "previousWrite"),
            ),
        )
        for (accessEntry in listOf("StrongAccessEntry", "WeakAccessEntry")) {
            put(
                "$guavaCache\$$accessEntry",
                Normalization(
                    dropped = setOf("nextAccess", "previousAccess"),
                    links = setOf("nextAccess", "previousAccess"),
                ),
            )
        }
        for (writeEntry in listOf("StrongWriteEntry", "WeakWriteEntry")) {
            put(
                "$guavaCache\$$writeEntry",
                Normalization(
                    dropped = setOf("nextWrite", "previousWrite"),
                    links = setOf("nextWrite", "previousWrite"),
                ),
            )
        }
        for (accessWriteEntry in listOf("StrongAccessWriteEntry", "WeakAccessWriteEntry")) {
            put(
                "$guavaCache\$$accessWriteEntry",
                Normalization(
                    dropped = setOf("nextAccess", "previousAccess", "nextWrite", "previousWrite"),
                    links = setOf("nextAccess", "previousAccess", "nextWrite"),
                ),
            )
        }
        // Guava immutable-map bucket chains (entries array lists all content).
        put(
            "$IMMUTABLE_MAP_ENTRY\$NonTerminalImmutableMapEntry",
            Normalization(
                dropped = setOf("nextInKeyBucket", "nextInValueBucket"),
                projectedName = IMMUTABLE_MAP_ENTRY,
            ),
        )
        // Reflective copy chains: copy.root points at the VM's root duplicate.
        put("java.lang.reflect.Method", Normalization(dropped = setOf("root")))
        put("java.lang.reflect.Constructor", Normalization(dropped = setOf("root")))
        put("java.lang.reflect.Field", Normalization(dropped = setOf("root")))
        // Global VM bookkeeping chains, the `discovered` category: the dump
        // catches every Cleaner/Finalizer linked in one list, so the chain
        // position splits every DirectByteBuffer (and everything holding one).
        // The own next/prev drop; Reference.next stays mechanics.
        put("jdk.internal.ref.Cleaner", Normalization(dropped = setOf("next", "prev")))
        put("java.lang.ref.Finalizer", Normalization(dropped = setOf("next", "prev")))
        // Lazy coercion cache on attribute entries.
        put(
            "org.gradle.api.internal.attributes.DefaultImmutableAttributesEntry",
            Normalization(dropped = setOf("coercionCache")),
        )
        // Keep-only containers: the named field is the content backbone.
        put(CONCURRENT_HASH_MAP, Normalization(kind = PlanKind.KEEP_ONLY, kept = setOf("table")))
        put(REGULAR_IMMUTABLE_MAP, Normalization(kind = PlanKind.KEEP_ONLY, kept = setOf("entries")))
        put(
            "org.gradle.api.internal.attributes.DefaultImmutableAttributesContainer",
            Normalization(kind = PlanKind.KEEP_ONLY, kept = setOf("hierarchy")),
        )
        // View objects are lazily instantiated; a map's identity must not
        // depend on whether keySet()/values()/entrySet() was ever called.
        val viewClasses = listOf(
            "$REGULAR_IMMUTABLE_MAP\$KeySet",
            "$REGULAR_IMMUTABLE_MAP\$Values",
            "com.google.common.collect.ImmutableMapEntrySet\$RegularEntrySet",
            "java.util.HashMap\$KeySet",
            "java.util.HashMap\$Values",
            "java.util.HashMap\$EntrySet",
            "java.util.LinkedHashMap\$LinkedKeySet",
            "java.util.LinkedHashMap\$LinkedValues",
            "java.util.LinkedHashMap\$LinkedEntrySet",
            "java.util.TreeMap\$KeySet",
            "java.util.TreeMap\$Values",
            "java.util.TreeMap\$EntrySet",
            "java.util.Hashtable\$KeySet",
            "java.util.Hashtable\$ValueCollection",
            "java.util.Hashtable\$EntrySet",
            "java.util.WeakHashMap\$KeySet",
            "java.util.WeakHashMap\$Values",
            "java.util.WeakHashMap\$EntrySet",
            "java.util.IdentityHashMap\$KeySet",
            "java.util.IdentityHashMap\$Values",
            "java.util.IdentityHashMap\$EntrySet",
            "$CONCURRENT_HASH_MAP\$KeySetView",
            "$CONCURRENT_HASH_MAP\$ValuesView",
            "$CONCURRENT_HASH_MAP\$EntrySetView",
        )
        for (viewClass in viewClasses) put(viewClass, Normalization(kind = PlanKind.NO_EDGES))
    }

    /**
     * Super-chain rules: public containers whose subclasses inherit the same
     * technical fields (a Properties is a Hashtable, a _Decorated map is a
     * LinkedHashMap). The most-derived ancestor with a rule wins. View fields
     * are lazy-init state; backbone fields expand to the element set at
     * assembly, insertion/balance order intentionally absent.
     */
    private val INHERITED_RULES: Map<String, Normalization> = mapOf(
        "java.util.HashMap" to Normalization(dropped = setOf("entrySet", "keySet", "values")),
        "java.util.LinkedHashMap" to Normalization(
            dropped = setOf("head", "tail", "entrySet", "keySet", "values"),
        ),
        "java.util.TreeMap" to Normalization(
            dropped = setOf("entrySet", "navigableKeySet", "descendingMap", "keySet", "values"),
            expand = setOf("root"),
        ),
        "java.util.Hashtable" to Normalization(dropped = setOf("keySet", "entrySet", "values")),
        "java.util.WeakHashMap" to Normalization(dropped = setOf("entrySet", "keySet", "values")),
        "java.util.IdentityHashMap" to Normalization(dropped = setOf("entrySet", "keySet", "values")),
        "java.util.LinkedList" to Normalization(expand = setOf("first", "last")),
        "java.util.concurrent.ConcurrentLinkedQueue" to Normalization(expand = setOf("head", "tail")),
    )

    /** Backbone arrays whose entry-node elements expand to full chains. */
    private val NODE_TABLE_ARRAYS = setOf(
        "$HASH_MAP_NODE[]",
        "$CONCURRENT_HASH_MAP_NODE[]",
        "java.util.Hashtable\$Entry[]",
        "java.util.WeakHashMap\$Entry[]",
        "org.codehaus.groovy.runtime.metaclass.MetaMethodIndex\$Entry[]",
    )

    /** Entry arrays flattened to a distinct element set. */
    private val ENTRY_SET_ARRAYS = setOf(
        "$IMMUTABLE_MAP_ENTRY[]",
        "java.util.Map\$Entry[]",
    )

    fun build(
        classStore: HprofClassStore,
        concealmentRules: FieldConcealmentRules,
        filterReferenceMechanics: Boolean,
        progress: (String) -> Unit,
    ): Array<ClassExtractionPlan> {
        val referenceRoots = classStore.requireByName(JAVA_LANG_REF_REFERENCE)
        val isReferenceSubtype = classStore.subtypesOf(referenceRoots)
        progress("plans: ${referenceRoots.size} java.lang.ref.Reference class dumps")

        val mechanicsNames = if (filterReferenceMechanics) {
            REFERENCE_MECHANICS_FIELDS
        } else {
            setOf("discovered")
        }

        // Concealment rules match a class and all subclasses by source name.
        val concealedNamesByClass = HashMap<Int, MutableSet<String>>()
        for ((ruleClassName, fields) in concealmentRules.fieldsByClass) {
            val ruleRoots = classStore.requireByName(ruleClassName.name)
            if (ruleRoots.isEmpty()) {
                progress("plans: WARNING conceal rule ${ruleClassName.name} matches no class")
                continue
            }
            val matches = classStore.subtypesOf(ruleRoots)
            var matchCount = 0
            for (classIndex in 0 until classStore.size) {
                if (matches[classIndex]) {
                    concealedNamesByClass.getOrPut(classIndex) { HashSet() }.addAll(fields)
                    matchCount++
                }
            }
            progress("plans: conceal ${ruleClassName.name}+subclasses=$matchCount drop $fields")
        }

        return Array(classStore.size) { classIndex ->
            buildPlan(
                classStore[classIndex],
                normalizationOf(classStore, classIndex),
                isReferenceSubtype[classIndex],
                mechanicsNames,
                concealedNamesByClass[classIndex].orEmpty(),
            )
        }
    }

    /** Exact rule for the class itself, else the nearest super-chain container rule. */
    private fun normalizationOf(classStore: HprofClassStore, classIndex: Int): Normalization? {
        val exact = EXACT_RULES[classStore[classIndex].name]
        if (exact != null) return exact
        var cursor = classIndex
        while (cursor >= 0) {
            val inherited = INHERITED_RULES[classStore[cursor].name]
            if (inherited != null) return inherited
            cursor = classStore[cursor].superIndex
        }
        return null
    }

    private fun buildPlan(
        hprofClass: HprofClass,
        normalization: Normalization?,
        isReferenceSubtype: Boolean,
        mechanicsNames: Set<String>,
        concealedNames: Set<String>,
    ): ClassExtractionPlan {
        val name = hprofClass.name
        if (name.endsWith("[]")) return buildArrayPlan(hprofClass)

        val kind = normalization?.kind ?: PlanKind.PLAIN
        val droppedNames = normalization?.dropped.orEmpty()
        val keptNames = normalization?.kept.orEmpty()
        val linkNames = normalization?.links.orEmpty()
        val expandNames = normalization?.expand.orEmpty()

        val readOffsets = ArrayList<Int>()
        val dropOffsets = ArrayList<Int>()
        val keepOffsets = ArrayList<Int>()
        val mechanicsOffsets = ArrayList<Int>()
        val concealedOffsets = ArrayList<Int>()
        val linkOffsets = ArrayList<Int>()
        val expandOffsets = ArrayList<Int>()
        val fieldOffsets = hprofClass.referenceFieldOffsets
        val fieldNames = hprofClass.referenceFieldNames
        // A built-in name matches only its first (most-derived) occurrence, so
        // a shadowed super field (WeakHashMap$Entry.next vs Reference.next)
        // keeps its own treatment.
        val consumedBuiltinNames = HashSet<String>()
        for (fieldIndex in fieldOffsets.indices) {
            val fieldName = fieldNames[fieldIndex]
            val fieldOffset = fieldOffsets[fieldIndex]
            when {
                fieldName in concealedNames -> concealedOffsets.add(fieldOffset)
                fieldName in droppedNames && consumedBuiltinNames.add(fieldName) -> {
                    dropOffsets.add(fieldOffset)
                    if (fieldName in linkNames) linkOffsets.add(fieldOffset)
                }
                fieldName in expandNames && consumedBuiltinNames.add(fieldName) ->
                    expandOffsets.add(fieldOffset)
                isReferenceSubtype && fieldName in mechanicsNames -> mechanicsOffsets.add(fieldOffset)
                else -> {
                    readOffsets.add(fieldOffset)
                    if (fieldName in keptNames) keepOffsets.add(fieldOffset)
                }
            }
        }
        check(linkOffsets.size <= MAX_LINK_FIELDS) {
            "$name has ${linkOffsets.size} link fields; the link table stores $MAX_LINK_FIELDS"
        }

        return ClassExtractionPlan(
            kind = kind,
            graphClassName = normalization?.projectedName ?: name,
            shallowSize = hprofClass.shallowSize,
            readOffsets = readOffsets.toIntArray(),
            dropOffsets = dropOffsets.toIntArray(),
            keepOffsets = keepOffsets.toIntArray(),
            mechanicsOffsets = mechanicsOffsets.toIntArray(),
            concealedOffsets = concealedOffsets.toIntArray(),
            isEntryNode = linkNames.isNotEmpty() || normalization?.transparent == true,
            linkOffsets = linkOffsets.toIntArray(),
            expandOffsets = expandOffsets.toIntArray(),
            isTransparentEntryNode = normalization?.transparent == true,
            isExcludedClassObject = name == JAVA_LANG_CLASS,
        )
    }

    private fun buildArrayPlan(hprofClass: HprofClass): ClassExtractionPlan {
        val name = hprofClass.name
        val elementName = name.removeSuffix("[]")
        val isPrimitive = elementName in PRIMITIVE_NAMES && !elementName.endsWith("[]")
        val kind = when {
            isPrimitive -> PlanKind.PRIMITIVE_ARRAY
            name in NODE_TABLE_ARRAYS -> PlanKind.NODE_TABLE
            name in ENTRY_SET_ARRAYS -> PlanKind.ENTRY_ARRAY_SET
            else -> PlanKind.OBJECT_ARRAY
        }
        return ClassExtractionPlan(
            kind = kind,
            graphClassName = name,
            shallowSize = 0,
            readOffsets = EMPTY,
            dropOffsets = EMPTY,
            keepOffsets = EMPTY,
            mechanicsOffsets = EMPTY,
            concealedOffsets = EMPTY,
            isEntryNode = false,
            linkOffsets = EMPTY,
            expandOffsets = EMPTY,
            isTransparentEntryNode = false,
            isExcludedClassObject = false,
        )
    }

    private val EMPTY = IntArray(0)
    private val PRIMITIVE_NAMES =
        setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")
}

internal fun FieldConcealmentRules.classNames(): Set<ClassName> = fieldsByClass.keys
