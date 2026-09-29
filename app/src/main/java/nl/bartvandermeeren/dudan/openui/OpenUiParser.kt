package nl.bartvandermeeren.dudan.openui

/**
 * OpenUI Lang (openui.com, spec v0.5): a line-oriented language in which a model describes a UI as
 * `name = Expression` statements, for example `root = Card([header, chart])`. This is a port of the
 * reference parser in @openuidev/lang-core: a lexer, a statement splitter and a Pratt expression
 * parser. Like the reference, it closes unfinished strings and brackets first, so a reply still
 * streaming in parses into as much UI as has arrived.
 */
sealed interface Expr {
    data class Str(val value: String) : Expr
    data class Num(val value: Double) : Expr
    data class Bool(val value: Boolean) : Expr
    data object Null : Expr
    data class Arr(val items: List<Expr>) : Expr
    data class Obj(val entries: List<Pair<String, Expr>>) : Expr
    /** A reference to another statement, or to an @Each loop variable. */
    data class Ref(val name: String) : Expr
    /** A reactive `$variable`; [name] keeps the dollar sign. */
    data class StateRef(val name: String) : Expr
    /** `Name(args)`; [builtin] when it was written as `@Name(args)`. */
    data class Call(val name: String, val args: List<Expr>, val builtin: Boolean) : Expr
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
    data class Unary(val op: String, val operand: Expr) : Expr
    data class Ternary(val condition: Expr, val then: Expr, val otherwise: Expr) : Expr
    data class Member(val target: Expr, val field: String) : Expr
    data class Index(val target: Expr, val index: Expr) : Expr
    data class Assign(val target: String, val value: Expr) : Expr
}

/**
 * A parsed program. [statements] keeps source order; [rootId] is the statement to render.
 * [incomplete] is true when the source ended inside a string or bracket, which is normal while it streams.
 */
class OpenUiProgram(
    val statements: Map<String, Expr>,
    val stateIds: Set<String>,
    val rootId: String?,
    val incomplete: Boolean,
) {
    val isEmpty: Boolean get() = rootId == null
}

object OpenUiParser {
    /** The chat library's root component; the entry point when no statement is called `root`. */
    const val ROOT_COMPONENT = "Card"

    fun parse(source: String): OpenUiProgram {
        val (closed, incomplete) = autoClose(stripComments(source))
        val statements = LinkedHashMap<String, Expr>()
        val stateIds = mutableSetOf<String>()
        for (raw in split(tokenize(closed))) {
            val expr = ExpressionParser(raw.tokens).parse()
            // A later definition wins, as it does in the reference parser.
            statements.remove(raw.id)
            statements[raw.id] = expr
            if (raw.kind == Kind.StateVar) stateIds += raw.id
        }
        return OpenUiProgram(statements, stateIds, pickRoot(statements), incomplete)
    }

    private fun pickRoot(statements: Map<String, Expr>): String? {
        if (statements.isEmpty()) return null
        if ("root" in statements) return "root"
        val components = statements.filter { (id, expr) -> !id.startsWith("$") && expr is Expr.Call && !expr.builtin }
        return components.entries.firstOrNull { (it.value as Expr.Call).name == ROOT_COMPONENT }?.key
            ?: components.keys.firstOrNull()
            ?: statements.keys.first()
    }

    // ---- Source clean-up ------------------------------------------------------------------------

    /** Drops `//` comments outside strings. The reference lexer has none, but the spec's own examples use them. */
    internal fun stripComments(source: String): String {
        if (!source.contains("//")) return source
        val out = StringBuilder(source.length)
        var quote: Char? = null
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (quote != null) {
                out.append(c)
                if (c == '\\' && i + 1 < source.length) {
                    out.append(source[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') quote = c
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                // A URL's "://" is only ever inside a string, so this is a comment.
                while (i < source.length && source[i] != '\n') i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Closes an unfinished string and any open brackets, so partial input parses. */
    internal fun autoClose(input: String): Pair<String, Boolean> {
        val stack = ArrayDeque<Char>()
        var quote: Char? = null
        var escaped = false
        for (c in input) {
            if (escaped) {
                escaped = false
                continue
            }
            if (quote != null) {
                if (c == '\\') escaped = true else if (c == quote) quote = null
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(', '[', '{' -> stack.addLast(c)
                ')' -> if (stack.lastOrNull() == '(') stack.removeLast()
                ']' -> if (stack.lastOrNull() == '[') stack.removeLast()
                '}' -> if (stack.lastOrNull() == '{') stack.removeLast()
            }
        }
        if (quote == null && stack.isEmpty()) return input to false
        val out = StringBuilder(input)
        if (quote != null) {
            if (escaped) out.append('\\')
            out.append(quote)
        }
        while (stack.isNotEmpty()) {
            out.append(
                when (stack.removeLast()) {
                    '(' -> ')'
                    '[' -> ']'
                    else -> '}'
                },
            )
        }
        return out.toString() to true
    }

    // ---- Lexer ----------------------------------------------------------------------------------

    internal enum class Kind {
        Newline, LParen, RParen, LBrack, RBrack, LBrace, RBrace, Comma, Colon, Equals,
        True, False, Null, Eof, Str, Num, Ident, Type, StateVar, Builtin, Dot,
        Plus, Minus, Star, Slash, Percent, EqEq, NotEq, Greater, Less, GreaterEq, LessEq, And, Or, Not, Question,
    }

    internal class Token(val kind: Kind, val text: String = "", val number: Double = 0.0)

    private val valueKinds = setOf(
        Kind.Num, Kind.Str, Kind.Ident, Kind.Type, Kind.RParen, Kind.RBrack, Kind.True, Kind.False, Kind.Null,
        Kind.StateVar, Kind.Builtin,
    )

    private fun Char.isIdentStart() = this in 'a'..'z' || this in 'A'..'Z' || this == '_'
    private fun Char.isIdentPart() = isIdentStart() || this in '0'..'9'

    internal fun tokenize(src: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = src.length
        fun single(kind: Kind) {
            tokens += Token(kind)
            i++
        }
        fun pair(next: Char, double: Kind, one: Kind) {
            if (i + 1 < n && src[i + 1] == next) {
                tokens += Token(double)
                i += 2
            } else {
                tokens += Token(one)
                i++
            }
        }
        while (i < n) {
            while (i < n && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r')) i++
            if (i >= n) break
            val c = src[i]
            when {
                c == '\n' -> single(Kind.Newline)
                c == '(' -> single(Kind.LParen)
                c == ')' -> single(Kind.RParen)
                c == '[' -> single(Kind.LBrack)
                c == ']' -> single(Kind.RBrack)
                c == '{' -> single(Kind.LBrace)
                c == '}' -> single(Kind.RBrace)
                c == ',' -> single(Kind.Comma)
                c == ':' -> single(Kind.Colon)
                c == '.' -> single(Kind.Dot)
                c == '?' -> single(Kind.Question)
                c == '+' -> single(Kind.Plus)
                c == '*' -> single(Kind.Star)
                c == '/' -> single(Kind.Slash)
                c == '%' -> single(Kind.Percent)
                c == '=' -> pair('=', Kind.EqEq, Kind.Equals)
                c == '!' -> pair('=', Kind.NotEq, Kind.Not)
                c == '>' -> pair('=', Kind.GreaterEq, Kind.Greater)
                c == '<' -> pair('=', Kind.LessEq, Kind.Less)
                c == '&' -> pair('&', Kind.And, Kind.And)
                c == '|' -> pair('|', Kind.Or, Kind.Or)
                c == '"' || c == '\'' -> {
                    val (value, end) = readString(src, i)
                    tokens += Token(Kind.Str, value)
                    i = end
                }
                // A minus after a value is subtraction; anywhere else it starts a negative number.
                c == '-' && tokens.lastOrNull()?.kind.let { it == null || it !in valueKinds } && i + 1 < n && src[i + 1].isDigit() -> {
                    i = readNumber(src, i, tokens)
                }
                c == '-' -> single(Kind.Minus)
                c.isDigit() -> i = readNumber(src, i, tokens)
                (c == '$' || c == '@') && i + 1 < n && src[i + 1].isIdentStart() -> {
                    val start = i + 1
                    i = start
                    while (i < n && src[i].isIdentPart()) i++
                    tokens += if (c == '$') Token(Kind.StateVar, src.substring(start - 1, i)) else Token(Kind.Builtin, src.substring(start, i))
                }
                c.isIdentStart() -> {
                    val start = i
                    while (i < n && src[i].isIdentPart()) i++
                    tokens += when (val word = src.substring(start, i)) {
                        "true" -> Token(Kind.True)
                        "false" -> Token(Kind.False)
                        "null" -> Token(Kind.Null)
                        else -> Token(if (c in 'A'..'Z') Kind.Type else Kind.Ident, word)
                    }
                }
                else -> i++ // Skip anything else, such as '#' or an emoji outside a string.
            }
        }
        tokens += Token(Kind.Eof)
        return tokens
    }

    private fun readNumber(src: String, from: Int, tokens: MutableList<Token>): Int {
        var i = from
        val n = src.length
        if (src[i] == '-') i++
        while (i < n && src[i].isDigit()) i++
        if (i + 1 < n && src[i] == '.' && src[i + 1].isDigit()) {
            i++
            while (i < n && src[i].isDigit()) i++
        }
        if (i < n && (src[i] == 'e' || src[i] == 'E')) {
            i++
            if (i < n && (src[i] == '+' || src[i] == '-')) i++
            while (i < n && src[i].isDigit()) i++
        }
        tokens += Token(Kind.Num, number = src.substring(from, i).toDoubleOrNull() ?: 0.0)
        return i
    }

    /** Reads a quoted string with JSON-style escapes. Returns the value and the index after it. */
    private fun readString(src: String, from: Int): Pair<String, Int> {
        val quote = src[from]
        val out = StringBuilder()
        var i = from + 1
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                val e = src[i + 1]
                i += 2
                when (e) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        val hex = src.substring(i, minOf(i + 4, src.length))
                        val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 4
                        }
                    }
                    else -> out.append(e)
                }
                continue
            }
            i++
            if (c == quote) return out.toString() to i
            out.append(c)
        }
        return out.toString() to i
    }

    // ---- Statements -----------------------------------------------------------------------------

    internal class RawStatement(val id: String, val kind: Kind, val tokens: List<Token>)

    /** Splits tokens into `id = expression` statements at newlines outside brackets and ternaries. */
    internal fun split(tokens: List<Token>): List<RawStatement> {
        val statements = mutableListOf<RawStatement>()
        var pos = 0
        fun skipLine() {
            while (pos < tokens.size && tokens[pos].kind != Kind.Newline && tokens[pos].kind != Kind.Eof) pos++
        }
        while (pos < tokens.size) {
            while (pos < tokens.size && tokens[pos].kind == Kind.Newline) pos++
            if (pos >= tokens.size || tokens[pos].kind == Kind.Eof) break
            val head = tokens[pos]
            if (head.kind != Kind.Ident && head.kind != Kind.Type && head.kind != Kind.StateVar) {
                skipLine()
                continue
            }
            pos++
            if (pos >= tokens.size || tokens[pos].kind != Kind.Equals) {
                skipLine()
                continue
            }
            pos++
            val expr = mutableListOf<Token>()
            var depth = 0
            var ternary = 0
            while (pos < tokens.size && tokens[pos].kind != Kind.Eof) {
                val kind = tokens[pos].kind
                if (kind == Kind.Newline && depth <= 0 && ternary <= 0) {
                    var peek = pos + 1
                    while (peek < tokens.size && tokens[peek].kind == Kind.Newline) peek++
                    val next = tokens.getOrNull(peek)?.kind
                    if (next == Kind.Question) {
                        pos++
                        continue
                    }
                    break
                }
                if (kind == Kind.Newline) {
                    pos++
                    continue
                }
                when (kind) {
                    Kind.LParen, Kind.LBrack, Kind.LBrace -> depth++
                    Kind.RParen, Kind.RBrack, Kind.RBrace -> if (depth > 0) depth--
                    Kind.Question -> if (depth == 0) ternary++
                    Kind.Colon -> if (depth == 0 && ternary > 0) ternary--
                    else -> Unit
                }
                expr += tokens[pos++]
            }
            if (expr.isNotEmpty()) statements += RawStatement(head.text, head.kind, expr)
        }
        return statements
    }

    // ---- Expressions ----------------------------------------------------------------------------

    private class ExpressionParser(private val tokens: List<Token>) {
        private var pos = 0
        private val eof = Token(Kind.Eof)

        private fun cur(): Token = tokens.getOrElse(pos) { eof }
        private fun peek(offset: Int): Token = tokens.getOrElse(pos + offset) { eof }
        private fun advance(): Token = cur().also { pos++ }
        private fun eat(kind: Kind) {
            if (cur().kind == kind) pos++
        }

        fun parse(): Expr = expression(0)

        private fun precedence(token: Token): Int = when (token.kind) {
            Kind.Question -> 1
            Kind.Or -> 2
            Kind.And -> 3
            Kind.EqEq, Kind.NotEq -> 4
            Kind.Greater, Kind.Less, Kind.GreaterEq, Kind.LessEq -> 5
            Kind.Plus, Kind.Minus -> 6
            Kind.Star, Kind.Slash, Kind.Percent -> 7
            Kind.Dot, Kind.LBrack -> 9
            else -> 0
        }

        private fun expression(minPrecedence: Int): Expr {
            var left = prefix()
            while (precedence(cur()) > minPrecedence) left = infix(left)
            return left
        }

        private fun prefix(): Expr {
            val token = cur()
            return when (token.kind) {
                Kind.Str -> advance().let { Expr.Str(it.text) }
                Kind.Num -> advance().let { Expr.Num(it.number) }
                Kind.True -> advance().let { Expr.Bool(true) }
                Kind.False -> advance().let { Expr.Bool(false) }
                Kind.Null -> advance().let { Expr.Null }
                Kind.LBrack -> array()
                Kind.LBrace -> obj()
                Kind.StateVar -> {
                    advance()
                    if (cur().kind == Kind.Equals) {
                        advance()
                        Expr.Assign(token.text, expression(0))
                    } else {
                        Expr.StateRef(token.text)
                    }
                }
                Kind.Type, Kind.Builtin -> if (peek(1).kind == Kind.LParen) call(builtin = token.kind == Kind.Builtin) else advance().let { Expr.Ref(it.text) }
                Kind.Ident -> advance().let { Expr.Ref(it.text) }
                Kind.Not -> {
                    advance()
                    Expr.Unary("!", expression(8))
                }
                Kind.Minus -> {
                    advance()
                    Expr.Unary("-", expression(8))
                }
                Kind.LParen -> {
                    advance()
                    expression(0).also { eat(Kind.RParen) }
                }
                else -> {
                    advance()
                    Expr.Null
                }
            }
        }

        private fun infix(left: Expr): Expr {
            val token = advance()
            return when (token.kind) {
                Kind.Plus -> Expr.Binary("+", left, expression(6))
                Kind.Minus -> Expr.Binary("-", left, expression(6))
                Kind.Star -> Expr.Binary("*", left, expression(7))
                Kind.Slash -> Expr.Binary("/", left, expression(7))
                Kind.Percent -> Expr.Binary("%", left, expression(7))
                Kind.EqEq -> Expr.Binary("==", left, expression(4))
                Kind.NotEq -> Expr.Binary("!=", left, expression(4))
                Kind.Greater -> Expr.Binary(">", left, expression(5))
                Kind.Less -> Expr.Binary("<", left, expression(5))
                Kind.GreaterEq -> Expr.Binary(">=", left, expression(5))
                Kind.LessEq -> Expr.Binary("<=", left, expression(5))
                Kind.And -> Expr.Binary("&&", left, expression(3))
                Kind.Or -> Expr.Binary("||", left, expression(2))
                Kind.Question -> {
                    val then = expression(0)
                    eat(Kind.Colon)
                    Expr.Ternary(left, then, expression(0))
                }
                Kind.Dot -> {
                    val field = cur()
                    advance()
                    val name = when (field.kind) {
                        Kind.Ident, Kind.Type, Kind.Str -> field.text
                        Kind.Num -> formatNumber(field.number)
                        Kind.StateVar -> field.text.removePrefix("$")
                        else -> "?"
                    }
                    Expr.Member(left, name)
                }
                Kind.LBrack -> {
                    val index = expression(0)
                    eat(Kind.RBrack)
                    Expr.Index(left, index)
                }
                else -> left
            }
        }

        private fun call(builtin: Boolean): Expr {
            val name = advance().text
            eat(Kind.LParen)
            val args = mutableListOf<Expr>()
            while (cur().kind != Kind.RParen && cur().kind != Kind.Eof) {
                args += expression(0)
                if (cur().kind == Kind.Comma) advance()
            }
            eat(Kind.RParen)
            return Expr.Call(name, args, builtin)
        }

        private fun array(): Expr {
            advance()
            val items = mutableListOf<Expr>()
            while (cur().kind != Kind.RBrack && cur().kind != Kind.Eof) {
                items += expression(0)
                if (cur().kind == Kind.Comma) advance()
            }
            eat(Kind.RBrack)
            return Expr.Arr(items)
        }

        private fun obj(): Expr {
            advance()
            val entries = mutableListOf<Pair<String, Expr>>()
            while (cur().kind != Kind.RBrace && cur().kind != Kind.Eof) {
                val keyToken = advance()
                val key = when (keyToken.kind) {
                    Kind.Ident, Kind.Str, Kind.Type -> keyToken.text
                    Kind.Num -> formatNumber(keyToken.number)
                    Kind.StateVar -> keyToken.text.removePrefix("$")
                    else -> "?"
                }
                eat(Kind.Colon)
                entries += key to expression(0)
                if (cur().kind == Kind.Comma) advance()
            }
            eat(Kind.RBrace)
            return Expr.Obj(entries)
        }
    }
}

/** Formats a number the way JavaScript's String(number) does for everyday values: 7, not 7.0. */
fun formatNumber(value: Double): String = when {
    value.isNaN() -> "NaN"
    value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
    value == Math.floor(value) && kotlin.math.abs(value) < 1e15 -> value.toLong().toString()
    else -> value.toString()
}
