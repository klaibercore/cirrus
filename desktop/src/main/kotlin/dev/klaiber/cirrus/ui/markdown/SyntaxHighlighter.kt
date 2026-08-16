package dev.klaiber.cirrus.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.klaiber.cirrus.ui.theme.CodeColors

/**
 * Lexer-driven syntax highlighting for fenced code blocks.
 *
 * A hand-written scanner is used instead of regex passes because regexes cannot tell a `//`
 * inside a string literal from a real comment, which is exactly the case that looks broken.
 * Unknown languages fall back to a C-like profile, which degrades gracefully.
 */
object SyntaxHighlighter {

    fun highlight(code: String, language: String?, colors: CodeColors): AnnotatedString {
        val normalized = language?.lowercase()?.trim().orEmpty()
        return when (normalized) {
            "html", "xml", "svg", "xhtml" -> highlightMarkup(code, colors)
            "json", "json5", "jsonc" -> highlightJson(code, colors)
            "diff", "patch" -> highlightDiff(code, colors)
            else -> highlightGeneric(code, specFor(normalized), colors)
        }
    }

    // ---- Generic scanner -------------------------------------------------------------------

    private data class Span(val start: Int, val end: Int, val color: Color, val bold: Boolean = false)

    private fun highlightGeneric(
        code: String,
        spec: LanguageSpec,
        colors: CodeColors,
    ): AnnotatedString {
        val spans = mutableListOf<Span>()
        var i = 0

        while (i < code.length) {
            val c = code[i]

            // Line comments.
            val lineComment = spec.lineComments.firstOrNull { code.startsWith(it, i) }
            if (lineComment != null) {
                val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                spans += Span(i, end, colors.comment, bold = false)
                i = end
                continue
            }

            // Block comments.
            val blockComment = spec.blockComment
            if (blockComment != null && code.startsWith(blockComment.first, i)) {
                val closeIndex = code.indexOf(blockComment.second, i + blockComment.first.length)
                val end = if (closeIndex == -1) code.length else closeIndex + blockComment.second.length
                spans += Span(i, end, colors.comment)
                i = end
                continue
            }

            // Strings, including triple-quoted forms where the language has them.
            if (c in spec.stringDelimiters) {
                val end = readString(code, i, c, spec.tripleQuoted)
                spans += Span(i, end, colors.string)
                i = end
                continue
            }

            // Numbers: leading digit, or a decimal point followed by a digit.
            if (c.isDigit() || (c == '.' && code.getOrNull(i + 1)?.isDigit() == true)) {
                val end = readNumber(code, i)
                spans += Span(i, end, colors.number)
                i = end
                continue
            }

            // Annotations, attributes and decorators.
            if ((c == '@' || c == '#') && spec.annotationPrefixes.contains(c) &&
                code.getOrNull(i + 1)?.isJavaIdentifierPartCompat() == true
            ) {
                var end = i + 1
                while (end < code.length && code[end].isJavaIdentifierPartCompat()) end++
                spans += Span(i, end, colors.attribute)
                i = end
                continue
            }

            // Identifiers and keywords.
            if (c.isJavaIdentifierStartCompat()) {
                var end = i
                while (end < code.length && code[end].isJavaIdentifierPartCompat()) end++
                val word = code.substring(i, end)
                val next = code.nextNonSpace(end)

                val color = when {
                    word in spec.keywords -> colors.keyword
                    word in spec.builtinTypes -> colors.type
                    next == '(' -> colors.function
                    word.firstOrNull()?.isUpperCase() == true && spec.capitalizedAreTypes -> colors.type
                    else -> null
                }
                if (color != null) {
                    spans += Span(i, end, color, bold = word in spec.keywords)
                }
                i = end
                continue
            }

            if (!c.isWhitespace() && !c.isLetterOrDigit()) {
                spans += Span(i, i + 1, colors.punctuation)
            }
            i++
        }

        return buildAnnotated(code, spans, colors.plain)
    }

    private fun readString(code: String, start: Int, delimiter: Char, tripleQuoted: Boolean): Int {
        val isTriple = tripleQuoted &&
            code.startsWith("$delimiter$delimiter$delimiter", start)
        if (isTriple) {
            val closeIndex = code.indexOf("$delimiter$delimiter$delimiter", start + 3)
            return if (closeIndex == -1) code.length else closeIndex + 3
        }

        var i = start + 1
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i += 2
                delimiter -> return i + 1
                // An unterminated literal ends at the newline rather than swallowing the file.
                '\n' -> return i
                else -> i++
            }
        }
        return code.length
    }

    private fun readNumber(code: String, start: Int): Int {
        var i = start
        if (code.startsWith("0x", i, ignoreCase = true) || code.startsWith("0b", i, ignoreCase = true)) {
            i += 2
            while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
            return i
        }
        var seenExponent = false
        while (i < code.length) {
            val c = code[i]
            when {
                c.isDigit() || c == '_' || c == '.' -> i++
                (c == 'e' || c == 'E') && !seenExponent -> {
                    seenExponent = true
                    i++
                    if (code.getOrNull(i) == '+' || code.getOrNull(i) == '-') i++
                }
                c.isLetter() -> {
                    // Numeric suffixes such as 10L, 1.5f, 3u.
                    i++
                    while (i < code.length && code[i].isLetter()) i++
                    return i
                }
                else -> return i
            }
        }
        return i
    }

    // ---- Markup ----------------------------------------------------------------------------

    private fun highlightMarkup(code: String, colors: CodeColors): AnnotatedString {
        val spans = mutableListOf<Span>()
        var i = 0

        while (i < code.length) {
            if (code.startsWith("<!--", i)) {
                val close = code.indexOf("-->", i)
                val end = if (close == -1) code.length else close + 3
                spans += Span(i, end, colors.comment)
                i = end
                continue
            }
            if (code[i] == '<') {
                val close = code.indexOf('>', i)
                val end = if (close == -1) code.length else close + 1
                highlightTag(code, i, end, colors, spans)
                i = end
                continue
            }
            i++
        }
        return buildAnnotated(code, spans, colors.plain)
    }

    private fun highlightTag(
        code: String,
        start: Int,
        end: Int,
        colors: CodeColors,
        spans: MutableList<Span>,
    ) {
        spans += Span(start, start + 1, colors.punctuation)
        var i = start + 1
        if (code.getOrNull(i) == '/' || code.getOrNull(i) == '!' || code.getOrNull(i) == '?') i++

        val nameStart = i
        while (i < end && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == ':' || code[i] == '_')) i++
        if (i > nameStart) spans += Span(nameStart, i, colors.keyword, bold = true)

        while (i < end) {
            when {
                code[i] == '"' || code[i] == '\'' -> {
                    val stringEnd = readString(code, i, code[i], tripleQuoted = false).coerceAtMost(end)
                    spans += Span(i, stringEnd, colors.string)
                    i = stringEnd
                }
                code[i].isLetter() -> {
                    val attrStart = i
                    while (i < end && (code[i].isLetterOrDigit() || code[i] == '-' || code[i] == ':' || code[i] == '_')) i++
                    spans += Span(attrStart, i, colors.attribute)
                }
                else -> i++
            }
        }
    }

    // ---- JSON ------------------------------------------------------------------------------

    private fun highlightJson(code: String, colors: CodeColors): AnnotatedString {
        val spans = mutableListOf<Span>()
        var i = 0

        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> {
                    val end = readString(code, i, '"', tripleQuoted = false)
                    // A string immediately followed by ':' is a key, not a value.
                    val isKey = code.nextNonSpace(end) == ':'
                    spans += Span(i, end, if (isKey) colors.attribute else colors.string)
                    i = end
                }
                c.isDigit() || (c == '-' && code.getOrNull(i + 1)?.isDigit() == true) -> {
                    val end = readNumber(code, if (c == '-') i + 1 else i)
                    spans += Span(i, end, colors.number)
                    i = end
                }
                c.isLetter() -> {
                    var end = i
                    while (end < code.length && code[end].isLetter()) end++
                    val word = code.substring(i, end)
                    if (word == "true" || word == "false" || word == "null") {
                        spans += Span(i, end, colors.keyword, bold = true)
                    }
                    i = end
                }
                !c.isWhitespace() -> {
                    spans += Span(i, i + 1, colors.punctuation)
                    i++
                }
                else -> i++
            }
        }
        return buildAnnotated(code, spans, colors.plain)
    }

    // ---- Diff ------------------------------------------------------------------------------

    private fun highlightDiff(code: String, colors: CodeColors): AnnotatedString {
        val spans = mutableListOf<Span>()
        var offset = 0
        code.split('\n').forEach { line ->
            val end = offset + line.length
            val color = when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> colors.keyword
                line.startsWith("+") -> colors.string
                line.startsWith("-") -> colors.number
                line.startsWith("diff ") || line.startsWith("index ") -> colors.comment
                else -> null
            }
            if (color != null) spans += Span(offset, end, color)
            offset = end + 1
        }
        return buildAnnotated(code, spans, colors.plain)
    }

    // ---- Shared ----------------------------------------------------------------------------

    private fun buildAnnotated(
        code: String,
        spans: List<Span>,
        defaultColor: Color,
    ): AnnotatedString = buildAnnotatedStringSafely(code, spans, defaultColor)

    private fun buildAnnotatedStringSafely(
        code: String,
        spans: List<Span>,
        defaultColor: Color,
    ): AnnotatedString {
        val builder = AnnotatedString.Builder(code)
        builder.addStyle(SpanStyle(color = defaultColor), 0, code.length)
        spans.forEach { span ->
            val start = span.start.coerceIn(0, code.length)
            val end = span.end.coerceIn(start, code.length)
            if (start == end) return@forEach
            builder.addStyle(
                SpanStyle(
                    color = span.color,
                    fontWeight = if (span.bold) FontWeight.Medium else null,
                    fontStyle = if (span.color == defaultColor) null else FontStyle.Normal,
                ),
                start,
                end,
            )
        }
        return builder.toAnnotatedString()
    }

    private fun String.nextNonSpace(from: Int): Char? {
        var i = from
        while (i < length && this[i].isWhitespace()) i++
        return getOrNull(i)
    }

    // `Character.isJavaIdentifier*` treats `$` and `_` as identifier characters, which matches
    // every language profile here closely enough.
    private fun Char.isJavaIdentifierStartCompat(): Boolean = isLetter() || this == '_' || this == '$'

    private fun Char.isJavaIdentifierPartCompat(): Boolean =
        isLetterOrDigit() || this == '_' || this == '$'

    // ---- Language profiles -------------------------------------------------------------------

    private data class LanguageSpec(
        val keywords: Set<String>,
        val builtinTypes: Set<String> = emptySet(),
        val lineComments: List<String> = listOf("//"),
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val stringDelimiters: Set<Char> = setOf('"', '\''),
        val tripleQuoted: Boolean = false,
        val annotationPrefixes: Set<Char> = setOf('@'),
        val capitalizedAreTypes: Boolean = true,
    )

    private fun specFor(language: String): LanguageSpec = when (language) {
        "kotlin", "kt", "kts" -> KOTLIN
        "java" -> JAVA
        "python", "py" -> PYTHON
        "javascript", "js", "jsx", "mjs" -> JAVASCRIPT
        "typescript", "ts", "tsx" -> TYPESCRIPT
        "go", "golang" -> GO
        "rust", "rs" -> RUST
        "swift" -> SWIFT
        "c", "h" -> C
        "cpp", "c++", "cc", "hpp" -> CPP
        "csharp", "cs" -> CSHARP
        "ruby", "rb" -> RUBY
        "php" -> PHP
        "sql" -> SQL
        "bash", "sh", "shell", "zsh", "fish" -> SHELL
        "yaml", "yml" -> YAML
        "toml", "ini" -> TOML
        "dockerfile", "docker" -> DOCKERFILE
        "gradle", "groovy" -> GROOVY
        "dart" -> DART
        "css", "scss", "less" -> CSS
        else -> DEFAULT
    }

    private val KOTLIN = LanguageSpec(
        keywords = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
            "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
            "init", "param", "property", "receiver", "set", "setparam", "value", "where",
            "actual", "abstract", "annotation", "companion", "const", "crossinline", "data",
            "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
            "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected",
            "public", "reified", "sealed", "suspend", "tailrec", "vararg", "it",
        ),
        builtinTypes = setOf(
            "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String",
            "Unit", "Any", "Nothing", "List", "Map", "Set", "Array", "MutableList", "MutableMap",
        ),
        tripleQuoted = true,
    )

    private val JAVA = LanguageSpec(
        keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record",
            "sealed", "permits", "yield", "true", "false", "null",
        ),
        builtinTypes = setOf("String", "Integer", "Long", "Double", "Boolean", "Object", "List", "Map"),
    )

    private val PYTHON = LanguageSpec(
        keywords = setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
            "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
            "with", "yield", "True", "False", "None", "self", "cls", "match", "case",
        ),
        builtinTypes = setOf(
            "int", "float", "str", "bool", "list", "dict", "set", "tuple", "bytes", "print",
            "len", "range", "type", "isinstance", "super", "open", "enumerate", "zip",
        ),
        lineComments = listOf("#"),
        blockComment = null,
        tripleQuoted = true,
        annotationPrefixes = setOf('@'),
    )

    private val JAVASCRIPT = LanguageSpec(
        keywords = setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
            "if", "import", "in", "instanceof", "let", "new", "of", "return", "static", "super",
            "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield",
            "true", "false", "null", "undefined",
        ),
        builtinTypes = setOf("console", "Promise", "Array", "Object", "String", "Number", "Math", "JSON"),
        stringDelimiters = setOf('"', '\'', '`'),
    )

    private val TYPESCRIPT = JAVASCRIPT.copy(
        keywords = JAVASCRIPT.keywords + setOf(
            "abstract", "any", "as", "boolean", "declare", "enum", "implements", "interface",
            "is", "keyof", "namespace", "never", "number", "private", "protected", "public",
            "readonly", "string", "symbol", "type", "unknown", "satisfies",
        ),
    )

    private val GO = LanguageSpec(
        keywords = setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
            "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
            "return", "select", "struct", "switch", "type", "var", "nil", "true", "false",
        ),
        builtinTypes = setOf(
            "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint32",
            "uint64", "float32", "float64", "bool", "byte", "rune", "error", "any",
        ),
        stringDelimiters = setOf('"', '\'', '`'),
    )

    private val RUST = LanguageSpec(
        keywords = setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
            "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
            "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
            "trait", "true", "type", "unsafe", "use", "where", "while",
        ),
        builtinTypes = setOf(
            "i8", "i16", "i32", "i64", "i128", "u8", "u16", "u32", "u64", "u128", "usize",
            "isize", "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result",
        ),
        annotationPrefixes = setOf('#'),
    )

    private val SWIFT = LanguageSpec(
        keywords = setOf(
            "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func",
            "import", "init", "inout", "internal", "let", "open", "operator", "private",
            "protocol", "public", "rethrows", "static", "struct", "subscript", "typealias",
            "var", "break", "case", "continue", "default", "defer", "do", "else", "fallthrough",
            "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while", "as",
            "catch", "false", "is", "nil", "super", "self", "throw", "throws", "true", "try",
            "async", "await", "some", "any", "actor",
        ),
        builtinTypes = setOf("Int", "Double", "Float", "String", "Bool", "Array", "Dictionary", "Set"),
    )

    private val C = LanguageSpec(
        keywords = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
            "switch", "typedef", "union", "unsigned", "void", "volatile", "while",
        ),
        builtinTypes = setOf("size_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "bool", "NULL"),
        annotationPrefixes = setOf('#'),
        capitalizedAreTypes = false,
    )

    private val CPP = C.copy(
        keywords = C.keywords + setOf(
            "class", "namespace", "template", "typename", "using", "public", "private",
            "protected", "virtual", "override", "final", "new", "delete", "this", "true",
            "false", "nullptr", "try", "catch", "throw", "constexpr", "decltype", "noexcept",
            "explicit", "friend", "mutable", "operator", "static_cast", "dynamic_cast",
            "const_cast", "reinterpret_cast", "co_await", "co_return", "co_yield", "concept",
        ),
        builtinTypes = C.builtinTypes + setOf("std", "string", "vector", "map", "set", "unique_ptr", "shared_ptr"),
        capitalizedAreTypes = true,
    )

    private val CSHARP = LanguageSpec(
        keywords = setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
            "class", "const", "continue", "decimal", "default", "delegate", "do", "double",
            "else", "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float",
            "for", "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal",
            "is", "lock", "long", "namespace", "new", "null", "object", "operator", "out",
            "override", "params", "private", "protected", "public", "readonly", "ref", "return",
            "sbyte", "sealed", "short", "sizeof", "static", "string", "struct", "switch", "this",
            "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
            "using", "var", "virtual", "void", "volatile", "while", "async", "await", "record",
        ),
    )

    private val RUBY = LanguageSpec(
        keywords = setOf(
            "alias", "and", "begin", "break", "case", "class", "def", "defined?", "do", "else",
            "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not",
            "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef",
            "unless", "until", "when", "while", "yield", "require", "attr_accessor", "puts",
        ),
        lineComments = listOf("#"),
        blockComment = null,
        annotationPrefixes = emptySet(),
    )

    private val PHP = LanguageSpec(
        keywords = setOf(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
            "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
            "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile",
            "extends", "final", "finally", "fn", "for", "foreach", "function", "global", "goto",
            "if", "implements", "include", "instanceof", "insteadof", "interface", "isset",
            "list", "match", "namespace", "new", "or", "print", "private", "protected", "public",
            "readonly", "require", "return", "static", "switch", "throw", "trait", "try", "unset",
            "use", "var", "while", "xor", "yield", "true", "false", "null",
        ),
        lineComments = listOf("//", "#"),
    )

    private val SQL = LanguageSpec(
        keywords = setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "JOIN", "INNER", "LEFT",
            "RIGHT", "FULL", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "UNION", "ALL", "DISTINCT", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN",
            "LIKE", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "PRIMARY", "KEY", "FOREIGN",
            "REFERENCES", "DEFAULT", "UNIQUE", "CONSTRAINT", "WITH", "RETURNING", "CASCADE",
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "alter", "drop", "join", "left", "inner", "on", "group", "by",
            "order", "having", "limit", "and", "or", "not", "null", "as", "distinct",
        ),
        lineComments = listOf("--"),
        annotationPrefixes = emptySet(),
        capitalizedAreTypes = false,
    )

    private val SHELL = LanguageSpec(
        keywords = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
            "esac", "function", "return", "in", "select", "time", "export", "local", "readonly",
            "source", "alias", "unset", "echo", "cd", "set", "trap", "exit", "shift", "eval",
        ),
        lineComments = listOf("#"),
        blockComment = null,
        stringDelimiters = setOf('"', '\'', '`'),
        annotationPrefixes = emptySet(),
        capitalizedAreTypes = false,
    )

    private val YAML = LanguageSpec(
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
        lineComments = listOf("#"),
        blockComment = null,
        annotationPrefixes = emptySet(),
        capitalizedAreTypes = false,
    )

    private val TOML = YAML

    private val DOCKERFILE = LanguageSpec(
        keywords = setOf(
            "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY",
            "ENTRYPOINT", "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL",
            "HEALTHCHECK", "SHELL", "AS",
        ),
        lineComments = listOf("#"),
        blockComment = null,
        annotationPrefixes = emptySet(),
        capitalizedAreTypes = false,
    )

    private val GROOVY = LanguageSpec(
        keywords = KOTLIN.keywords + JAVA.keywords + setOf("def", "task", "apply", "dependencies"),
        stringDelimiters = setOf('"', '\'', '`'),
        tripleQuoted = true,
    )

    private val DART = LanguageSpec(
        keywords = setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class",
            "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else",
            "enum", "export", "extends", "extension", "external", "factory", "false", "final",
            "finally", "for", "get", "hide", "if", "implements", "import", "in", "interface",
            "is", "late", "library", "mixin", "new", "null", "on", "operator", "part", "required",
            "rethrow", "return", "sealed", "set", "show", "static", "super", "switch", "sync",
            "this", "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield",
        ),
        builtinTypes = setOf("int", "double", "String", "bool", "List", "Map", "Set", "Future", "Stream", "Widget"),
    )

    private val CSS = LanguageSpec(
        keywords = setOf(
            "important", "media", "import", "keyframes", "supports", "font-face", "charset",
            "and", "not", "only", "from", "to",
        ),
        lineComments = emptyList(),
        annotationPrefixes = setOf('@'),
        capitalizedAreTypes = false,
    )

    private val DEFAULT = LanguageSpec(
        keywords = KOTLIN.keywords + JAVA.keywords + JAVASCRIPT.keywords,
        lineComments = listOf("//", "#"),
        capitalizedAreTypes = true,
    )
}
