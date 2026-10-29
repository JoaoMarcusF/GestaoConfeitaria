package com.gestaoconfeitaria.core

/**
 * JSON mínimo, sem dependências (o mesmo código roda no Android e no computador).
 * Objetos viram LinkedHashMap<String, Any?>, listas viram ArrayList<Any?>,
 * números viram Long (inteiros) ou Double.
 */
object Json {
    fun parse(s: String): Any? = Parser(s).run { val v = valor(); espacos(); if (i != s.length) erro("texto após o JSON"); v }

    fun stringify(v: Any?): String = StringBuilder().also { escrever(it, v) }.toString()

    private fun escrever(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null")
                         else if (v == Math.rint(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> escrever(sb, v.toDouble())
            is Number -> sb.append(v.toLong())
            is Map<*, *> -> {
                sb.append('{'); var primeiro = true
                for ((k, x) in v) { if (!primeiro) sb.append(','); primeiro = false; str(sb, k.toString()); sb.append(':'); escrever(sb, x) }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('['); var primeiro = true
                for (x in v) { if (!primeiro) sb.append(','); primeiro = false; escrever(sb, x) }
                sb.append(']')
            }
            is Array<*> -> escrever(sb, v.asList())
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun erro(m: String): Nothing = throw IllegalArgumentException("JSON inválido ($m) na posição $i")
        fun espacos() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun valor(): Any? {
            espacos()
            if (i >= s.length) erro("fim inesperado")
            return when (val c = s[i]) {
                '{' -> objeto(); '[' -> lista(); '"' -> texto()
                't' -> palavra("true", true); 'f' -> palavra("false", false); 'n' -> palavra("null", null)
                else -> if (c == '-' || c.isDigit()) numero() else erro("caractere '$c'")
            }
        }
        fun palavra(p: String, v: Any?): Any? { if (!s.startsWith(p, i)) erro("esperava $p"); i += p.length; return v }
        fun objeto(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; espacos()
            if (s[i] == '}') { i++; return m }
            while (true) {
                espacos(); if (s[i] != '"') erro("esperava chave")
                val k = texto(); espacos(); if (s[i] != ':') erro("esperava :"); i++
                m[k] = valor(); espacos()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> erro("esperava , ou }") }
            }
        }
        fun lista(): List<Any?> {
            val l = ArrayList<Any?>(); i++; espacos()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += valor(); espacos()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> erro("esperava , ou ]") }
            }
        }
        fun texto(): String {
            val sb = StringBuilder(); i++
            while (true) {
                if (i >= s.length) erro("texto sem fim")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun numero(): Any {
            val ini = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(ini, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }
}

// ---------------------------------------------------------------- leitura confortável de mapas

typealias Obj = Map<String, Any?>
typealias MObj = MutableMap<String, Any?>

fun Obj.str(k: String, padrao: String = ""): String = when (val v = this[k]) { null -> padrao; is String -> v; else -> v.toString() }
fun Obj.num(k: String, padrao: Double = 0.0): Double = when (val v = this[k]) {
    is Number -> v.toDouble(); is String -> v.replace(',', '.').toDoubleOrNull() ?: padrao; else -> padrao
}
fun Obj.numOuNull(k: String): Double? = when (val v = this[k]) {
    is Number -> v.toDouble(); is String -> v.replace(',', '.').toDoubleOrNull(); else -> null
}
fun Obj.long(k: String, padrao: Long = 0): Long = (this[k] as? Number)?.toLong() ?: padrao
fun Obj.bool(k: String): Boolean = this[k] == true
@Suppress("UNCHECKED_CAST") fun Obj.obj(k: String): Obj? = this[k] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST") fun Obj.lista(k: String): List<Obj> = (this[k] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
@Suppress("UNCHECKED_CAST") fun Obj.strs(k: String): List<String> = (this[k] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

fun obj(vararg pares: Pair<String, Any?>): MObj = linkedMapOf(*pares)

/** Cópia profunda (para não compartilhar mapas mutáveis entre threads). */
@Suppress("UNCHECKED_CAST")
fun copiaProfunda(v: Any?): Any? = when (v) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k.toString()] = copiaProfunda(x) } }
    is List<*> -> v.mapTo(ArrayList()) { copiaProfunda(it) }
    else -> v
}
