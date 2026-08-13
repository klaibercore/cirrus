package dev.klaiber.cirrus.ui.markdown.math

/**
 * Parses the LaTeX subset that chat models emit into a [MathNode] tree.
 *
 * Tolerant by design. Models truncate mid-formula while streaming, close the wrong brace, and
 * invent commands; none of that may throw, because the alternative to a slightly wrong formula on
 * screen is no answer at all. An unknown command degrades to its own name, an unclosed group ends
 * at the end of the input, and an unmatched `\right` is simply ignored.
 */
internal object MathParser {

    fun parse(latex: String): MathNode = Parser(latex).parseDocument()
}

private class Parser(private val source: String) {

    private var index = 0

    /**
     * A whole formula. `\\` at the top level makes it a stack of centred lines, which is how
     * multi-line display equations arrive when nobody bothered with an environment.
     */
    fun parseDocument(): MathNode {
        val lines = mutableListOf<MathNode>()
        val nodes = mutableListOf<MathNode>()

        while (true) {
            nodes += parseNodes()
            skipSpace()
            if (index >= source.length) break

            when {
                startsWith("\\\\") || startsWith("\\cr") -> {
                    index += if (startsWith("\\cr")) 3 else 2
                    skipOptionalLineSpacing()
                    lines += nodes.toList().asRow()
                    nodes.clear()
                }

                // Terminators that belong to nobody at this level — a stray `}`, an unmatched
                // `\right`, an `\end` without its `\begin`. Step over and keep what follows,
                // because half a formula on screen beats a formula that stops at the typo.
                startsWith("\\right") -> {
                    index += "\\right".length
                    readDelimiter()
                }

                startsWith("\\end") -> {
                    index += "\\end".length
                    readBraceName()
                }

                else -> index++
            }
        }

        if (lines.isEmpty()) return nodes.asRow()
        lines += nodes.asRow()
        return MathNode.Grid(
            rows = lines.filterNot { it == MathNode.Empty }.map { listOf(it) },
            alignment = GridAlign.CENTER,
        )
    }

    /** Parses until a terminator the caller owns: `}`, `&`, `\\`, `\right`, `\end`, or the end. */
    private fun parseNodes(): List<MathNode> {
        val nodes = mutableListOf<MathNode>()
        while (!atEnd()) {
            val node = parseAtom() ?: continue
            nodes += parseScripts(node)
        }
        return nodes
    }

    private fun atEnd(): Boolean {
        skipSpace()
        if (index >= source.length) return true
        val char = source[index]
        if (char == '}' || char == '&') return true
        return startsWith("\\\\") || startsWith("\\right") || startsWith("\\end") ||
            startsWith("\\cr")
    }

    /** One unit before scripts are attached. Null means "consumed, but produced nothing". */
    private fun parseAtom(): MathNode? {
        skipSpace()
        if (index >= source.length) return null
        val char = source[index]

        return when {
            char == '{' -> {
                index++
                val body = parseNodes().asRow()
                expect('}')
                body
            }

            char == '\\' -> parseCommand()

            char.isDigit() -> MathNode.Glyph(readNumber(), Atom.ORD, MathFont.UPRIGHT)

            char.isLetter() -> {
                index++
                // Single letters are variables, and variables are italic. That one rule does more
                // for making maths look like maths than any symbol in the table.
                MathNode.Glyph(char.toString(), Atom.ORD, MathFont.ITALIC)
            }

            else -> {
                index++
                symbolFor(char)
            }
        }
    }

    private fun symbolFor(char: Char): MathNode? = when (char) {
        // The hyphen-minus is a hyphen; maths wants the real minus sign, which is wider.
        '-' -> glyph("−")
        '*' -> glyph("∗")
        '\'' -> MathNode.Scripts(MathNode.Empty, superscript = glyph("′"))
        '~' -> MathNode.Space(SPACE_MEDIUM)
        '$' -> null
        else -> glyph(char.toString())
    }

    private fun glyph(text: String): MathNode = MathNode.Glyph(
        text = text,
        atom = MATH_ATOM_CLASSES[text] ?: Atom.ORD,
        font = MathFont.UPRIGHT,
    )

    private fun parseCommand(): MathNode? {
        index++ // the backslash
        if (index >= source.length) return null
        val name = readCommandName()

        MATH_SPACES[name]?.let { return MathNode.Space(it) }

        return when (name) {
            "frac", "dfrac", "tfrac", "cfrac" ->
                MathNode.Fraction(parseArgument(), parseArgument())

            "binom", "dbinom", "tbinom" -> MathNode.Fence(
                left = "(",
                body = MathNode.Fraction(parseArgument(), parseArgument(), rule = false),
                right = ")",
            )

            "sqrt" -> {
                val degree = parseOptionalArgument()
                MathNode.Root(parseArgument(), degree)
            }

            "text", "textrm", "textnormal", "mbox" -> MathNode.TextRun(readTextArgument())
            "textbf" -> MathNode.TextRun(readTextArgument(), bold = true)
            "textit", "emph" -> MathNode.TextRun(readTextArgument(), italic = true)

            "mathrm", "operatorname" -> styled(parseArgument(), MathFont.UPRIGHT, Atom.OP)
            "mathbf", "bm", "boldsymbol" -> styled(parseArgument(), MathFont.BOLD, Atom.ORD)
            "mathit" -> styled(parseArgument(), MathFont.ITALIC, Atom.ORD)
            "mathtt", "texttt" -> styled(parseArgument(), MathFont.MONO, Atom.ORD)
            "mathsf" -> styled(parseArgument(), MathFont.UPRIGHT, Atom.ORD)
            "mathbb" -> blackboard(parseArgument())
            "mathcal", "mathfrak", "mathscr" -> styled(parseArgument(), MathFont.ITALIC, Atom.ORD)

            "hat", "widehat" -> MathNode.Accent(parseArgument(), AccentKind.HAT)
            "bar", "overline" -> MathNode.Accent(parseArgument(), AccentKind.BAR)
            "vec", "overrightarrow" -> MathNode.Accent(parseArgument(), AccentKind.VEC)
            "tilde", "widetilde" -> MathNode.Accent(parseArgument(), AccentKind.TILDE)
            "dot" -> MathNode.Accent(parseArgument(), AccentKind.DOT)
            "ddot" -> MathNode.Accent(parseArgument(), AccentKind.DDOT)

            "left" -> parseFence()
            "begin" -> parseEnvironment(readBraceName())

            // Style and sizing hints have no meaning without a full layout model.
            "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle",
            "limits", "nolimits", "big", "Big", "bigg", "Bigg",
            "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "left.", "right.",
            -> null

            "underline", "boxed", "mathop", "phantom" -> parseArgument()

            "pmod" -> MathNode.Row(
                listOf(
                    MathNode.Space(SPACE_WIDE),
                    glyph("("),
                    MathNode.Glyph("mod", Atom.OP, MathFont.UPRIGHT),
                    MathNode.Space(SPACE_THIN),
                    parseArgument(),
                    glyph(")"),
                ),
            )

            in MATH_FUNCTIONS -> MathNode.Glyph(
                text = name,
                atom = Atom.OP,
                font = MathFont.UPRIGHT,
                limitsAbove = name in LIMIT_FUNCTIONS,
            )

            in MATH_BIG_OPERATORS -> MathNode.Glyph(
                text = MATH_SYMBOLS.getValue(name),
                atom = Atom.OP,
                font = MathFont.UPRIGHT,
                big = true,
                limitsAbove = true,
            )

            in MATH_INTEGRALS -> MathNode.Glyph(
                text = MATH_SYMBOLS.getValue(name),
                atom = Atom.OP,
                font = MathFont.UPRIGHT,
                big = true,
            )

            else -> {
                val symbol = MATH_SYMBOLS[name]
                when {
                    symbol != null -> glyph(symbol)
                    // An escaped punctuation mark: `\%`, `\{`, `\_`.
                    name.length == 1 && !name[0].isLetter() -> glyph(name)
                    // An unknown command reads better as its own name than as nothing at all.
                    else -> MathNode.Glyph(name, Atom.ORD, MathFont.UPRIGHT)
                }
            }
        }
    }

    /** Applies a font to every glyph in a parsed argument, and optionally re-classes the result. */
    private fun styled(node: MathNode, font: MathFont, atom: Atom): MathNode = when (node) {
        is MathNode.Glyph -> node.copy(font = font, atom = if (node.atom == Atom.ORD) atom else node.atom)
        is MathNode.Row -> {
            // A multi-glyph run set upright is a name — `\operatorname{softmax}` — so it should be
            // one atom rather than a string of them, or the spacing table pulls it apart.
            val text = node.children.filterIsInstance<MathNode.Glyph>()
                .takeIf { it.size == node.children.size }
                ?.joinToString("") { it.text }
            if (text != null) {
                MathNode.Glyph(text, atom, font)
            } else {
                MathNode.Row(node.children.map { styled(it, font, atom) })
            }
        }
        is MathNode.TextRun -> node
        else -> node
    }

    private fun blackboard(node: MathNode): MathNode {
        val text = (node as? MathNode.Glyph)?.text ?: return node
        return MathNode.Glyph(MATH_BLACKBOARD[text] ?: text, Atom.ORD, MathFont.DOUBLE_STRUCK)
    }

    /** `\left( … \right)`, sized to whatever ends up between the delimiters. */
    private fun parseFence(): MathNode {
        val left = readDelimiter()
        val body = parseNodes().asRow()
        var right: String? = null
        skipSpace()
        if (startsWith("\\right")) {
            index += "\\right".length
            right = readDelimiter()
        }
        return MathNode.Fence(left, body, right)
    }

    private fun readDelimiter(): String? {
        skipSpace()
        if (index >= source.length) return null
        if (source[index] == '\\') {
            val start = index
            index++
            val name = readCommandName()
            val spelling = "\\$name"
            return MATH_DELIMITERS[spelling] ?: run {
                index = start
                null
            }
        }
        val char = source[index].toString()
        val mapped = MATH_DELIMITERS[char] ?: return null
        index++
        return mapped.takeIf { it.isNotEmpty() }
    }

    private fun parseEnvironment(name: String): MathNode {
        // `array` carries a column specification nobody here honours, but it still has to be eaten.
        if (name.startsWith("array")) parseOptionalArgument()?.let { }
        if (name.startsWith("array")) skipBraceGroup()

        val rows = mutableListOf<List<MathNode>>()
        var current = mutableListOf<MathNode>()

        while (true) {
            current += parseNodes().asRow()
            skipSpace()
            when {
                index >= source.length -> {
                    rows += current
                    break
                }

                source[index] == '&' -> index++

                startsWith("\\\\") || startsWith("\\cr") -> {
                    index += if (startsWith("\\cr")) 3 else 2
                    skipOptionalLineSpacing()
                    rows += current
                    current = mutableListOf()
                }

                startsWith("\\end") -> {
                    index += "\\end".length
                    readBraceName()
                    rows += current
                    break
                }

                // A `}` that belongs to nothing here: consume it so the loop cannot spin.
                else -> index++
            }
        }

        val body = rows.filterNot { row -> row.all { it == MathNode.Empty } }
        val environment = ENVIRONMENTS[name.removeSuffix("*")]
            ?: EnvironmentShape(null, null, GridAlign.CENTER, false)

        return MathNode.Grid(
            rows = body.ifEmpty { listOf(listOf(MathNode.Empty)) },
            left = environment.left,
            right = environment.right,
            alignment = environment.alignment,
            alternating = environment.alternating,
        )
    }

    /** Superscripts, subscripts and primes, in whatever order they were written. */
    private fun parseScripts(base: MathNode): MathNode {
        var superscript: MathNode? = null
        var subscript: MathNode? = null
        var current = base

        while (true) {
            skipSpace()
            if (index >= source.length) break
            when (source[index]) {
                '^' -> {
                    index++
                    superscript = merge(superscript, parseArgument())
                }

                '_' -> {
                    index++
                    subscript = merge(subscript, parseArgument())
                }

                '\'' -> {
                    index++
                    superscript = merge(superscript, glyph("′"))
                }

                else -> break
            }
        }

        if (superscript == null && subscript == null) return current
        // `x'^2` attaches both to the same base rather than nesting scripts on a script.
        if (current is MathNode.Scripts && current.base == MathNode.Empty) {
            superscript = merge(current.superscript, superscript)
            subscript = merge(current.subscript, subscript)
            current = MathNode.Empty
        }
        return MathNode.Scripts(current, superscript, subscript)
    }

    private fun merge(existing: MathNode?, addition: MathNode?): MathNode? = when {
        existing == null -> addition
        addition == null -> existing
        else -> MathNode.Row(listOf(existing, addition))
    }

    /** A `{…}` group, or the single token that follows when the author left the braces off. */
    private fun parseArgument(): MathNode {
        skipSpace()
        if (index >= source.length) return MathNode.Empty
        if (source[index] == '{') {
            index++
            val body = parseNodes().asRow()
            expect('}')
            return body
        }
        return parseAtom() ?: MathNode.Empty
    }

    /** The `[n]` of `\sqrt[3]{x}`. */
    private fun parseOptionalArgument(): MathNode? {
        skipSpace()
        if (index >= source.length || source[index] != '[') return null
        val close = source.indexOf(']', index + 1)
        if (close < 0) return null
        val inner = source.substring(index + 1, close)
        index = close + 1
        return Parser(inner).parseDocument()
    }

    /** `\text{}` keeps its spaces and takes no notice of maths syntax inside. */
    private fun readTextArgument(): String {
        skipSpace()
        if (index >= source.length || source[index] != '{') {
            val atom = parseAtom()
            return (atom as? MathNode.Glyph)?.text.orEmpty()
        }
        index++
        val start = index
        var depth = 1
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val text = source.substring(start, index)
                        index++
                        return text
                    }
                }
            }
            index++
        }
        return source.substring(start)
    }

    private fun readBraceName(): String {
        skipSpace()
        if (index >= source.length || source[index] != '{') return ""
        val close = source.indexOf('}', index)
        if (close < 0) {
            index = source.length
            return ""
        }
        val name = source.substring(index + 1, close)
        index = close + 1
        return name
    }

    private fun skipBraceGroup() {
        skipSpace()
        if (index >= source.length || source[index] != '{') return
        var depth = 0
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        return
                    }
                }
            }
            index++
        }
    }

    /** `\\[6pt]` — a row gap this renderer decides for itself. */
    private fun skipOptionalLineSpacing() {
        skipSpace()
        if (index < source.length && source[index] == '[') {
            val close = source.indexOf(']', index)
            if (close >= 0) index = close + 1
        }
    }

    private fun readCommandName(): String {
        if (index >= source.length) return ""
        if (!source[index].isLetter()) {
            val char = source[index]
            index++
            return char.toString()
        }
        val start = index
        while (index < source.length && source[index].isLetter()) index++
        return source.substring(start, index)
    }

    private fun readNumber(): String {
        val start = index
        while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
        // A trailing full stop is the end of a sentence, not part of the number.
        if (index > start && source[index - 1] == '.') index--
        return source.substring(start, index)
    }

    private fun skipSpace() {
        while (index < source.length && (source[index] == ' ' || source[index] == '\n' ||
                source[index] == '\t' || source[index] == '\r')
        ) {
            index++
        }
    }

    private fun expect(char: Char) {
        if (index < source.length && source[index] == char) index++
    }

    private fun startsWith(prefix: String): Boolean = source.startsWith(prefix, index)
}

private data class EnvironmentShape(
    val left: String?,
    val right: String?,
    val alignment: GridAlign,
    val alternating: Boolean,
)

private val ENVIRONMENTS: Map<String, EnvironmentShape> = mapOf(
    "matrix" to EnvironmentShape(null, null, GridAlign.CENTER, false),
    "smallmatrix" to EnvironmentShape(null, null, GridAlign.CENTER, false),
    "pmatrix" to EnvironmentShape("(", ")", GridAlign.CENTER, false),
    "bmatrix" to EnvironmentShape("[", "]", GridAlign.CENTER, false),
    "Bmatrix" to EnvironmentShape("{", "}", GridAlign.CENTER, false),
    "vmatrix" to EnvironmentShape("|", "|", GridAlign.CENTER, false),
    "Vmatrix" to EnvironmentShape("‖", "‖", GridAlign.CENTER, false),
    "array" to EnvironmentShape(null, null, GridAlign.CENTER, false),
    "cases" to EnvironmentShape("{", null, GridAlign.START, false),
    "aligned" to EnvironmentShape(null, null, GridAlign.END, true),
    "align" to EnvironmentShape(null, null, GridAlign.END, true),
    "alignat" to EnvironmentShape(null, null, GridAlign.END, true),
    "split" to EnvironmentShape(null, null, GridAlign.END, true),
    "gathered" to EnvironmentShape(null, null, GridAlign.CENTER, false),
    "gather" to EnvironmentShape(null, null, GridAlign.CENTER, false),
    "equation" to EnvironmentShape(null, null, GridAlign.CENTER, false),
)

/** `\lim` and its relatives put their subscript underneath, without growing. */
private val LIMIT_FUNCTIONS = setOf("lim", "limsup", "liminf", "max", "min", "sup", "inf", "argmax", "argmin")

private val MATH_SPACES: Map<String, Float> = mapOf(
    "," to SPACE_THIN,
    ":" to SPACE_MEDIUM,
    ";" to SPACE_WIDE,
    "!" to -SPACE_THIN,
    " " to SPACE_MEDIUM,
    "quad" to 1f,
    "qquad" to 2f,
    "space" to SPACE_MEDIUM,
    "thinspace" to SPACE_THIN,
    "enspace" to 0.5f,
)

private const val SPACE_THIN = 3f / 18f
private const val SPACE_MEDIUM = 4f / 18f
private const val SPACE_WIDE = 5f / 18f
