package shapes.hprof

import shapes.graph.ClassName
import shapes.relaxation.FieldConcealmentRules

/** How one class's instances contribute edges to the structural graph. */
internal enum class PlanKind {
    /** All reference fields become edges; duplicates preserved. */
    PLAIN,

    /**
     * Named technical edges removed with alias protection, output deduplicated
     * (LinkedHashMap, HashMap\$Node, LinkedHashMap\$Entry, CHM\$Node,
     * NonTerminalImmutableMapEntry, DefaultImmutableAttributesEntry).
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

    /** Plain object array: non-null elements, duplicates preserved. */
    OBJECT_ARRAY,

    /** Entry arrays flattened to a distinct element set. */
    ENTRY_ARRAY_SET,

    /** Hash-table backbone: entry-node elements expand to full bucket chains. */
    NODE_TABLE,

    /** Primitive arrays: always leaves. */
    PRIMITIVE_ARRAY,
}

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
    /** This class is a hash-map entry node (chain member). */
    val isEntryNode: Boolean,
    /** Offsets of next/before/after links for entry nodes. */
    val linkOffsets: IntArray,
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
    private const val LINKED_HASH_MAP = "java.util.LinkedHashMap"
    private const val LINKED_HASH_MAP_ENTRY = "java.util.LinkedHashMap\$Entry"
    private const val HASH_MAP_NODE_ARRAY = "java.util.HashMap\$Node[]"
    private const val CONCURRENT_HASH_MAP = "java.util.concurrent.ConcurrentHashMap"
    private const val CONCURRENT_HASH_MAP_NODE = "java.util.concurrent.ConcurrentHashMap\$Node"
    private const val CONCURRENT_HASH_MAP_NODE_ARRAY = "$CONCURRENT_HASH_MAP_NODE[]"
    private const val IMMUTABLE_MAP_ENTRY_ARRAY = "com.google.common.collect.ImmutableMapEntry[]"
    private const val JAVA_MAP_ENTRY_ARRAY = "java.util.Map\$Entry[]"
    private const val REGULAR_IMMUTABLE_MAP = "com.google.common.collect.RegularImmutableMap"
    private const val REGULAR_IMMUTABLE_MAP_KEY_SET = "$REGULAR_IMMUTABLE_MAP\$KeySet"
    private const val REGULAR_IMMUTABLE_MAP_VALUES = "$REGULAR_IMMUTABLE_MAP\$Values"
    private const val IMMUTABLE_MAP_ENTRY_SET_REGULAR =
        "com.google.common.collect.ImmutableMapEntrySet\$RegularEntrySet"
    private const val IMMUTABLE_MAP_ENTRY = "com.google.common.collect.ImmutableMapEntry"
    private const val IMMUTABLE_MAP_ENTRY_NONTERMINAL =
        "$IMMUTABLE_MAP_ENTRY\$NonTerminalImmutableMapEntry"
    private const val ATTRIBUTES_ENTRY =
        "org.gradle.api.internal.attributes.DefaultImmutableAttributesEntry"
    private const val ATTRIBUTES_CONTAINER =
        "org.gradle.api.internal.attributes.DefaultImmutableAttributesContainer"
    private const val JAVA_LANG_CLASS = "java.lang.Class"
    private const val JAVA_LANG_REF_REFERENCE = "java.lang.ref.Reference"

    private val REFERENCE_MECHANICS_FIELDS = setOf("referent", "queue", "next", "discovered")

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
                isReferenceSubtype[classIndex],
                mechanicsNames,
                concealedNamesByClass[classIndex].orEmpty(),
            )
        }
    }

    private fun buildPlan(
        hprofClass: HprofClass,
        isReferenceSubtype: Boolean,
        mechanicsNames: Set<String>,
        concealedNames: Set<String>,
    ): ClassExtractionPlan {
        val name = hprofClass.name
        if (name.endsWith("[]")) return buildArrayPlan(hprofClass)

        val droppedNames: Set<String>
        val keptNames: Set<String>
        var kind: PlanKind
        when (name) {
            LINKED_HASH_MAP -> {
                kind = PlanKind.DROP_EDGES; droppedNames = setOf("head", "tail"); keptNames = emptySet()
            }
            HASH_MAP_NODE, CONCURRENT_HASH_MAP_NODE -> {
                kind = PlanKind.DROP_EDGES; droppedNames = setOf("next"); keptNames = emptySet()
            }
            LINKED_HASH_MAP_ENTRY -> {
                kind = PlanKind.DROP_EDGES
                droppedNames = setOf("next", "before", "after")
                keptNames = emptySet()
            }
            IMMUTABLE_MAP_ENTRY_NONTERMINAL -> {
                kind = PlanKind.DROP_EDGES
                droppedNames = setOf("nextInKeyBucket", "nextInValueBucket")
                keptNames = emptySet()
            }
            ATTRIBUTES_ENTRY -> {
                kind = PlanKind.DROP_EDGES; droppedNames = setOf("coercionCache"); keptNames = emptySet()
            }
            CONCURRENT_HASH_MAP -> {
                kind = PlanKind.KEEP_ONLY; droppedNames = emptySet(); keptNames = setOf("table")
            }
            REGULAR_IMMUTABLE_MAP -> {
                kind = PlanKind.KEEP_ONLY; droppedNames = emptySet(); keptNames = setOf("entries")
            }
            ATTRIBUTES_CONTAINER -> {
                kind = PlanKind.KEEP_ONLY; droppedNames = emptySet(); keptNames = setOf("hierarchy")
            }
            REGULAR_IMMUTABLE_MAP_KEY_SET,
            REGULAR_IMMUTABLE_MAP_VALUES,
            IMMUTABLE_MAP_ENTRY_SET_REGULAR -> {
                kind = PlanKind.NO_EDGES; droppedNames = emptySet(); keptNames = emptySet()
            }
            else -> {
                kind = PlanKind.PLAIN; droppedNames = emptySet(); keptNames = emptySet()
            }
        }

        val readOffsets = ArrayList<Int>()
        val dropOffsets = ArrayList<Int>()
        val keepOffsets = ArrayList<Int>()
        val mechanicsOffsets = ArrayList<Int>()
        val concealedOffsets = ArrayList<Int>()
        val linkOffsets = ArrayList<Int>()
        val fieldOffsets = hprofClass.referenceFieldOffsets
        val fieldNames = hprofClass.referenceFieldNames
        for (fieldIndex in fieldOffsets.indices) {
            val fieldName = fieldNames[fieldIndex]
            val fieldOffset = fieldOffsets[fieldIndex]
            when {
                fieldName in concealedNames -> concealedOffsets.add(fieldOffset)
                isReferenceSubtype && fieldName in mechanicsNames -> mechanicsOffsets.add(fieldOffset)
                fieldName in droppedNames -> {
                    dropOffsets.add(fieldOffset)
                    linkOffsets.add(fieldOffset)
                }
                else -> {
                    readOffsets.add(fieldOffset)
                    if (fieldName in keptNames) keepOffsets.add(fieldOffset)
                }
            }
        }

        val isEntryNode = name == HASH_MAP_NODE || name == LINKED_HASH_MAP_ENTRY ||
            name == CONCURRENT_HASH_MAP_NODE
        val graphName = if (name == IMMUTABLE_MAP_ENTRY_NONTERMINAL) IMMUTABLE_MAP_ENTRY else name
        return ClassExtractionPlan(
            kind = kind,
            graphClassName = graphName,
            shallowSize = hprofClass.shallowSize,
            readOffsets = readOffsets.toIntArray(),
            dropOffsets = dropOffsets.toIntArray(),
            keepOffsets = keepOffsets.toIntArray(),
            mechanicsOffsets = mechanicsOffsets.toIntArray(),
            concealedOffsets = concealedOffsets.toIntArray(),
            isEntryNode = isEntryNode,
            linkOffsets = linkOffsets.toIntArray(),
            isExcludedClassObject = name == JAVA_LANG_CLASS,
        )
    }

    private fun buildArrayPlan(hprofClass: HprofClass): ClassExtractionPlan {
        val name = hprofClass.name
        val elementName = name.removeSuffix("[]")
        val isPrimitive = elementName in PRIMITIVE_NAMES && !elementName.endsWith("[]")
        val kind = when {
            isPrimitive -> PlanKind.PRIMITIVE_ARRAY
            name == HASH_MAP_NODE_ARRAY || name == CONCURRENT_HASH_MAP_NODE_ARRAY ->
                PlanKind.NODE_TABLE
            name == IMMUTABLE_MAP_ENTRY_ARRAY || name == JAVA_MAP_ENTRY_ARRAY ->
                PlanKind.ENTRY_ARRAY_SET
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
            isExcludedClassObject = false,
        )
    }

    private val EMPTY = IntArray(0)
    private val PRIMITIVE_NAMES =
        setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")
}

internal fun FieldConcealmentRules.classNames(): Set<ClassName> = fieldsByClass.keys
