package shapes

import kotlinx.serialization.Serializable

/**
 * Class relaxation rules (SHAPES.md "Relaxed classes"): for selected classes
 * the exact structural key is deliberately weakened so that known
 * representation/state noise does not split shapes.
 *
 * Two levels, per class:
 *  - [CLASS_ONLY] ("class-only"): only the class matters. The object conses
 *    as a LEAF of its class — its children are consed normally (they stay in
 *    scope and keep their counts) but are not part of the parent's key.
 *    Use for infrastructure objects whose internal state is irrelevant to
 *    the owner graph (e.g. an HTTP client that may or may not have been
 *    instantiated yet).
 *  - [SET_CHILDREN] ("set-children"): the class and the SET of child shapes
 *    matter; child multiplicities collapse to 1. Use when duplicates/order
 *    of children are representation detail but the child composition is
 *    semantic.
 *
 * The rule list is a versioned TSV file: `class.name<TAB>class-only|set-children<TAB>reason`.
 * Keep it reviewable: every line must say WHICH merge it unblocks (the
 * reason column is required), and adding a class is a conscious decision —
 * a class-only rule hides everything below the class, always.
 */
object Relax {
    const val CLASS_ONLY = 1
    const val SET_CHILDREN = 2

    /** Level 3 keyword prefix: conceal one named field of a class (and its
     *  subclasses), applied at EXTRACTION time (MatExtract has field names,
     *  the analyzer's GRPH does not). The edge never enters the graph, so the
     *  target may drop out of the scope entirely — the surgical choice for a
     *  lazy/cache field whose presence is state, not structure. */
    const val CONCEAL_FIELD_PREFIX = "conceal-field:"

    fun levelName(level: Int): String = when (level) {
        CLASS_ONLY -> "class-only"
        SET_CHILDREN -> "set-children"
        else -> "exact"
    }

    /** Parsed rule file: [classRules] for the analyzer, [fieldRules]
     *  (class -> field names, applied at extraction) for MatExtract. */
    class Rules(
        val classRules: Map<String, Int>,
        val fieldRules: Map<String, Set<String>>,
    ) {
        fun isEmpty() = classRules.isEmpty() && fieldRules.isEmpty()
    }

    /** Parse the TSV rule file. Blank lines and `#` comments are ignored. */
    fun parse(text: String): Rules {
        val classRules = LinkedHashMap<String, Int>()
        val fieldRules = LinkedHashMap<String, Set<String>>()
        for ((lineNo, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t", limit = 3)
            require(parts.size >= 2) { "relax.tsv:${lineNo + 1}: expected 'class<TAB>level[<TAB>reason]', got: $raw" }
            require(parts.size == 3 && parts[2].isNotBlank()) {
                "relax.tsv:${lineNo + 1}: a reason is required (which merge does this rule unblock?): $raw"
            }
            val cls = parts[0].trim()
            val levelStr = parts[1].trim()
            if (levelStr.startsWith(CONCEAL_FIELD_PREFIX)) {
                val field = levelStr.removePrefix(CONCEAL_FIELD_PREFIX).trim()
                require(field.isNotEmpty()) { "relax.tsv:${lineNo + 1}: empty field name in $levelStr" }
                val prev = fieldRules.getOrPut(cls) { sortedSetOf() } as java.util.SortedSet
                require(prev.add(field)) { "relax.tsv:${lineNo + 1}: duplicate rule $cls.$field" }
                continue
            }
            val level = when (levelStr) {
                "class-only" -> CLASS_ONLY
                "set-children" -> SET_CHILDREN
                else -> error("relax.tsv:${lineNo + 1}: unknown level '$levelStr' (class-only|set-children|conceal-field:<name>)")
            }
            val prev = classRules.put(cls, level)
            require(prev == null || prev == level) { "relax.tsv:${lineNo + 1}: conflicting rules for $cls" }
        }
        return Rules(classRules, fieldRules)
    }

    /** class-table index -> level (0 = exact), for the graph's class table. */
    fun levels(classNames: Array<String>, rules: Map<String, Int>): IntArray {
        if (rules.isEmpty()) return IntArray(0)
        val levels = IntArray(classNames.size)
        val unknown = rules.keys.toMutableSet()
        for ((i, name) in classNames.withIndex()) {
            val lvl = rules[name]
            if (lvl != null) {
                levels[i] = lvl
                unknown.remove(name)
            }
        }
        for (name in rules.keys) if (name in unknown) {
            println("relax: WARNING rule for $name matches no class in this graph")
        }
        return levels
    }
}

/** Exact accounting of what relaxation concealed (for verification and the
 *  backend's edge-total validation: emitted edges = projected graph edges
 *  minus these). */
@Serializable
class RelaxStats {
    fun reset() {
        relaxedObjects = 0
        classOnlyHiddenEdges = 0
        setChildrenHiddenEdges = 0
        setChildrenConcealedObjects = 0
    }

    /** refs hidden by class-only rules (external refs of those objects). */
    var classOnlyHiddenEdges: Long = 0
    /** multiplicity collapse by set-children rules: Σ (refs − distinct child shapes). */
    var setChildrenHiddenEdges: Long = 0
    /** objects whose class is relaxed (any level). */
    var relaxedObjects: Long = 0
    /** quotient SCC: members hidden by the WL-color quotient (instance
     *  member count − quotient size), summed over SCC instances. The
     *  artifact's count for a quotient SCC is quotient-size × instances,
     *  so object totals need this counter to stay exact. */
    var setChildrenConcealedObjects: Long = 0
    val totalHidden: Long get() = classOnlyHiddenEdges + setChildrenHiddenEdges
}
