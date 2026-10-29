package com.gestaoconfeitaria.core

import java.text.Normalizer

/**
 * Unidades de medida.
 *
 * Tudo é convertido para uma unidade base: g (massa), ml (volume) ou un (contagem).
 * Ex.: na nota "CHOC.GT.MELK.BC.2,05KG" (1 UN por R$ 178,99) → 2050 g → R$ 87,31 por kg;
 * na receita "200 g de chocolate" → custo R$ 17,46.
 */
object Unidades {

    data class Medida(val valor: Double, val base: String)          // base: g | ml | un

    /** Conteúdo de UMA unidade comercial do item (ex.: 1 "UN" = 395 g). */
    data class Conteudo(val porUnidade: Medida, val fonte: String, val trecho: String)
    // fonte: "unidade" (a própria unidade da nota já é kg/g/L/ml), "descricao", "granel", "manual"

    /** Unidades aceitas nas receitas e no lançamento manual: nome → (fator, base). */
    val unidadesReceita = linkedMapOf(
        "g" to (1.0 to "g"), "kg" to (1000.0 to "g"), "mg" to (0.001 to "g"),
        "ml" to (1.0 to "ml"), "l" to (1000.0 to "ml"),
        "un" to (1.0 to "un"), "dz" to (12.0 to "un"),
        "xícara" to (240.0 to "ml"), "colher de sopa" to (15.0 to "ml"),
        "colher de sobremesa" to (10.0 to "ml"), "colher de chá" to (5.0 to "ml"),
        "copo americano" to (190.0 to "ml"),
    )

    private val sinonimos = mapOf(
        "G" to "g", "GR" to "g", "GRS" to "g", "GRAMA" to "g", "GRAMAS" to "g",
        "KG" to "kg", "KGS" to "kg", "K" to "kg", "KILO" to "kg", "KILOS" to "kg", "QUILO" to "kg",
        "MG" to "mg", "ML" to "ml", "MLS" to "ml",
        "L" to "l", "LT" to "l", "LTS" to "l", "LITRO" to "l", "LITROS" to "l",
        "UN" to "un", "UND" to "un", "UNID" to "un", "UNIDADE" to "un", "UNIDADES" to "un", "U" to "un",
        "PC" to "un", "PCS" to "un", "PECA" to "un", "DZ" to "dz", "DUZIA" to "dz",
        "XICARA" to "xícara", "XIC" to "xícara", "CS" to "colher de sopa", "COLHER DE SOPA" to "colher de sopa",
        "CC" to "colher de chá", "COLHER DE CHA" to "colher de chá", "COLHER DE SOBREMESA" to "colher de sobremesa",
    )

    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").uppercase().trim()

    /** "kg" → (1000, "g"); null se a unidade não for conhecida. */
    fun paraBase(unidade: String): Pair<Double, String>? {
        val u = unidade.trim()
        unidadesReceita[u.lowercase()]?.let { return it }
        val n = normalizar(u).replace(Regex("[^A-Z ]"), "").trim()
        return sinonimos[n]?.let { unidadesReceita[it] }
    }

    /** Converte uma quantidade numa unidade qualquer para a base. */
    fun converter(valor: Double, unidade: String): Medida? = paraBase(unidade)?.let { (f, b) -> Medida(valor * f, b) }

    // ------------------------------------------------------------ detecção a partir da nota

    private const val UNI = "KGS|KG|KILOS?|K|GRAMAS?|GRS|GR|G|MLS|ML|LITROS?|LTS|LT|L"
    // Pontos nas notas são separadores de abreviação ("KINDER.BUENO.39G"), então não bloqueiam o número
    private val reMultiplo = Regex("(?<![\\d,])(\\d{1,3})\\s?X\\s?(\\d+(?:[.,]\\d+)?)\\s?($UNI)(?![A-Z])")
    private val reTamanho = Regex("(?<![\\d,])(\\d+(?:[.,]\\d+)?)\\s?($UNI)(?![A-Z])")
    private val reContagem = Regex("(?:C/\\s?(\\d{1,4})(?!\\d)|(?<![A-Z\\d])C(\\d{2,4})(?![\\d.,])|(?<![\\d,])(\\d{1,4})\\s?(?:UN|UND|UNID|UNIDADES|UNDS|U)(?![A-Z]))")
    private val reDuzia = Regex("(?<![A-Z])(DZ|DUZIA)(?![A-Z])")
    private val reKgSolto = Regex("(?<![A-Z\\d])(KG|GRANEL)(?![A-Z])")

    /**
     * Descobre quanto vem em uma unidade comercial do item.
     * Devolve null quando não dá para saber — o app então pede o valor ao usuário.
     */
    fun detectar(descricao: String, unidadeComercial: String, quantidade: Double): Conteudo? {
        val u = normalizar(unidadeComercial).replace(Regex("[^A-Z]"), "")
        // 1) A unidade da nota já é de medida (ex.: 0,456 KG de banana)
        when (sinonimos[u]) {
            "kg" -> return Conteudo(Medida(1000.0, "g"), "unidade", unidadeComercial)
            "g" -> return Conteudo(Medida(1.0, "g"), "unidade", unidadeComercial)
            "l" -> return Conteudo(Medida(1000.0, "ml"), "unidade", unidadeComercial)
            "ml" -> return Conteudo(Medida(1.0, "ml"), "unidade", unidadeComercial)
            "dz" -> return Conteudo(Medida(12.0, "un"), "unidade", unidadeComercial)
        }
        val d = normalizar(descricao)

        // 2) Embalagem múltipla: "LEITE 12X1L", "6 X 200ML"
        reMultiplo.find(d)?.let { m ->
            val n = m.groupValues[1].toDouble()
            val med = medida(m.groupValues[2], m.groupValues[3]) ?: return@let
            return Conteudo(Medida(n * med.valor, med.base), "descricao", m.value)
        }
        // 3) Tamanho da embalagem: "395G", "2,05KG", "1L", "T650.650G" (pega o 650G)
        reTamanho.findAll(d).toList().lastOrNull()?.let { m ->
            val med = medida(m.groupValues[1], m.groupValues[2]) ?: return@let
            if (med.valor > 0) return Conteudo(med, "descricao", m.value)
        }
        // 4) Quantidade de unidades: "OVOS C/30", "FORMINHA 100UN"
        reContagem.find(d)?.let { m ->
            val n = m.groupValues.drop(1).first { it.isNotEmpty() }.toDouble()
            if (n > 0) return Conteudo(Medida(n, "un"), "descricao", m.value)
        }
        if (reDuzia.containsMatchIn(d)) return Conteudo(Medida(12.0, "un"), "descricao", "DZ")
        // 5) Vendido a granel por kg: "BANANA PRATA KG" com quantidade fracionada (0,876)
        if (reKgSolto.find(d)?.value == "KG" || (reKgSolto.containsMatchIn(d) && quantidade != Math.floor(quantidade)))
            return Conteudo(Medida(1000.0, "g"), "granel", "KG")
        return null
    }

    /**
     * Palpite para quando a detecção falha: número solto no fim da descrição
     * ("LEITE C.I.8 P.1.C.395" → 395 g). Só serve para pré-preencher; o usuário confirma.
     */
    fun sugestao(descricao: String): Medida? {
        val m = Regex("(?<![\\d,])(\\d{2,4})(?:[.,]\\d+)?\\s*$").find(normalizar(descricao).trimEnd('.', ' ')) ?: return null
        val v = m.groupValues[1].toDouble()
        return if (v in 20.0..5000.0) Medida(v, "g") else null
    }

    private fun medida(numero: String, unidade: String): Medida? {
        var t = numero
        // Ponto costuma ser separador de abreviação nas notas: "T650.650G" → 650
        if (t.contains('.')) {
            val (ini, fim) = t.split('.', limit = 2)
            t = if (ini.length > 1 || fim.length >= 3) fim else "$ini.$fim"
        }
        val v = t.replace(',', '.').toDoubleOrNull() ?: return null
        val (f, b) = unidadesReceita[sinonimos[unidade] ?: return null] ?: return null
        return Medida(v * f, b)
    }

    /** Fatores de conversão entre bases diferentes (ex.: ml → g) informados pelo usuário no insumo. */
    data class Equivalencias(val gPorMl: Double?, val gPorUn: Double?)

    /** Converte uma medida para a base do insumo. null = falta uma equivalência. */
    fun paraBaseDoInsumo(m: Medida, baseInsumo: String, eq: Equivalencias): Double? {
        if (m.base == baseInsumo) return m.valor
        val emGramas = when (m.base) {
            "g" -> m.valor
            "ml" -> eq.gPorMl?.let { m.valor * it }
            "un" -> eq.gPorUn?.let { m.valor * it }
            else -> null
        } ?: return null
        return when (baseInsumo) {
            "g" -> emGramas
            "ml" -> eq.gPorMl?.let { emGramas / it }
            "un" -> eq.gPorUn?.let { emGramas / it }
            else -> null
        }
    }

    fun nomeBase(base: String) = when (base) { "g" -> "kg"; "ml" -> "L"; else -> "un" }
    /** Preço por base (R$/g) mostrado por kg ou L, que é como o usuário pensa. */
    fun fatorExibicao(base: String) = if (base == "un") 1.0 else 1000.0
}
