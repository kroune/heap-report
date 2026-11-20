package shapes.relaxation

import kotlinx.serialization.Serializable
import shapes.graph.ClassName

/** How strongly an object's children participate in its structural shape key. */
enum class StructuralRelaxation {
    EXACT,
    CLASS_ONLY,
    SET_CHILDREN,
}

data class ShapeRelaxationRules(
    val byClass: Map<ClassName, StructuralRelaxation> = emptyMap(),
    /** Full-match patterns, applied in file order when no exact rule matches. */
    val byPattern: List<PatternRelaxation> = emptyList(),
) {
    fun isEmpty(): Boolean = byClass.isEmpty() && byPattern.isEmpty()
}

data class PatternRelaxation(
    val pattern: Regex,
    val relaxation: StructuralRelaxation,
)

data class FieldConcealmentRules(
    val fieldsByClass: Map<ClassName, Set<String>> = emptyMap(),
) {
    fun isEmpty(): Boolean = fieldsByClass.isEmpty()
}

data class RelaxationRules(
    val shapeRules: ShapeRelaxationRules = ShapeRelaxationRules(),
    val fieldConcealmentRules: FieldConcealmentRules = FieldConcealmentRules(),
) {
    fun isEmpty(): Boolean = shapeRules.isEmpty() && fieldConcealmentRules.isEmpty()
}

/** Resolves class-name rules against the compact graph's class table. */
class ShapeRelaxationPolicy(
    rules: ShapeRelaxationRules,
    classNames: List<ClassName>,
    onUnknownClass: (ClassName) -> Unit = {},
) {
    private val relaxationByClass: Array<StructuralRelaxation>

    init {
        if (rules.isEmpty()) {
            relaxationByClass = emptyArray()
        } else {
            val unknownClasses = rules.byClass.keys.toMutableSet()
            val matchedPatterns = BooleanArray(rules.byPattern.size)
            relaxationByClass = Array(classNames.size) { classIndex ->
                val className = classNames[classIndex]
                val exact = rules.byClass[className]
                if (exact != null) {
                    unknownClasses.remove(className)
                    exact
                } else {
                    var relaxation = StructuralRelaxation.EXACT
                    for ((patternIndex, patternRule) in rules.byPattern.withIndex()) {
                        if (patternRule.pattern.matches(className.name)) {
                            matchedPatterns[patternIndex] = true
                            relaxation = patternRule.relaxation
                            break
                        }
                    }
                    relaxation
                }
            }
            unknownClasses.forEach(onUnknownClass)
            rules.byPattern.forEachIndexed { patternIndex, patternRule ->
                if (!matchedPatterns[patternIndex]) {
                    onUnknownClass(ClassName("$REGEX_PREFIX${patternRule.pattern.pattern}"))
                }
            }
        }
    }

    val isEmpty: Boolean
        get() = relaxationByClass.isEmpty()

    fun relaxationOf(classIndex: Int): StructuralRelaxation =
        relaxationByClass.getOrElse(classIndex) { StructuralRelaxation.EXACT }
}

const val REGEX_PREFIX = "regex:"

/** Parser for `class.name<TAB>rule<TAB>reason` relaxation files. */
object RelaxationRulesTsvParser {
    private const val CONCEAL_FIELD_PREFIX = "conceal-field:"

    fun parse(text: String): RelaxationRules {
        val shapeRules = LinkedHashMap<ClassName, StructuralRelaxation>()
        val patternRules = LinkedHashMap<String, StructuralRelaxation>()
        val concealedFields = LinkedHashMap<ClassName, MutableSet<String>>()

        for ((lineIndex, rawLine) in text.lines().withIndex()) {
            val lineNumber = lineIndex + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val columns = line.split("\t", limit = 3)
            require(columns.size >= 2) {
                errorMessage(lineNumber, "expected 'class<TAB>rule[<TAB>reason]', got: $rawLine")
            }
            require(columns.size == 3 && columns[2].isNotBlank()) {
                errorMessage(lineNumber, "a reason is required: $rawLine")
            }

            val classColumn = columns[0].trim()
            val ruleName = columns[1].trim()
            if (classColumn.startsWith(REGEX_PREFIX)) {
                parsePatternRelaxation(ruleName, classColumn, patternRules, lineNumber)
            } else if (ruleName.startsWith(CONCEAL_FIELD_PREFIX)) {
                parseFieldConcealment(ruleName, ClassName(classColumn), concealedFields, lineNumber)
            } else {
                parseShapeRelaxation(ruleName, ClassName(classColumn), shapeRules, lineNumber)
            }
        }

        return RelaxationRules(
            shapeRules = ShapeRelaxationRules(
                byClass = shapeRules,
                byPattern = patternRules.map { (pattern, relaxation) ->
                    PatternRelaxation(Regex(pattern), relaxation)
                },
            ),
            fieldConcealmentRules = FieldConcealmentRules(concealedFields),
        )
    }

    private fun parsePatternRelaxation(
        ruleName: String,
        classColumn: String,
        patternRules: MutableMap<String, StructuralRelaxation>,
        lineNumber: Int,
    ) {
        require(!ruleName.startsWith(CONCEAL_FIELD_PREFIX)) {
            errorMessage(lineNumber, "conceal-field rules require an exact class name, not a regex")
        }
        val pattern = classColumn.removePrefix(REGEX_PREFIX).trim()
        require(pattern.isNotEmpty()) { errorMessage(lineNumber, "empty regex pattern") }
        runCatching { Regex(pattern) }.getOrElse { failure ->
            error(errorMessage(lineNumber, "invalid regex '$pattern': ${failure.message}"))
        }
        val relaxation = parseRelaxationLevel(ruleName, lineNumber)
        val previous = patternRules.put(pattern, relaxation)
        require(previous == null || previous == relaxation) {
            errorMessage(lineNumber, "conflicting rules for regex:$pattern")
        }
    }

    private fun parseFieldConcealment(
        ruleName: String,
        className: ClassName,
        concealedFields: MutableMap<ClassName, MutableSet<String>>,
        lineNumber: Int,
    ) {
        val fieldName = ruleName.removePrefix(CONCEAL_FIELD_PREFIX).trim()
        require(fieldName.isNotEmpty()) { errorMessage(lineNumber, "empty field name in $ruleName") }
        val fieldsForClass = concealedFields.getOrPut(className) { sortedSetOf() }
        require(fieldsForClass.add(fieldName)) {
            errorMessage(lineNumber, "duplicate rule $className.$fieldName")
        }
    }

    private fun parseRelaxationLevel(ruleName: String, lineNumber: Int): StructuralRelaxation =
        when (ruleName) {
            "class-only" -> StructuralRelaxation.CLASS_ONLY
            "set-children" -> StructuralRelaxation.SET_CHILDREN
            else -> error(
                errorMessage(
                    lineNumber,
                    "unknown rule '$ruleName' (class-only|set-children|conceal-field:<name>)",
                )
            )
        }

    private fun parseShapeRelaxation(
        ruleName: String,
        className: ClassName,
        shapeRules: MutableMap<ClassName, StructuralRelaxation>,
        lineNumber: Int,
    ) {
        val relaxation = parseRelaxationLevel(ruleName, lineNumber)
        val previous = shapeRules.put(className, relaxation)
        require(previous == null || previous == relaxation) {
            errorMessage(lineNumber, "conflicting rules for $className")
        }
    }

    private fun errorMessage(lineNumber: Int, message: String): String =
        "relax.tsv:$lineNumber: $message"
}

/** Exact accounting of structural information concealed by relaxation. */
@Serializable
class ShapeRelaxationStats {
    var classOnlyHiddenEdges: Long = 0
    var setChildrenHiddenEdges: Long = 0
    var relaxedObjects: Long = 0
    var setChildrenConcealedObjects: Long = 0

    fun reset() {
        relaxedObjects = 0
        classOnlyHiddenEdges = 0
        setChildrenHiddenEdges = 0
        setChildrenConcealedObjects = 0
    }
}
