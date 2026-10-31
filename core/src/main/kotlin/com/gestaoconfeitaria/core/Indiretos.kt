package com.gestaoconfeitaria.core

/**
 * Gastos indiretos das receitas: luz, gás, água e outras contas.
 *
 * - Equipamentos (coleção "equipamentos"): forno elétrico pela potência (W), fogão/forno a gás pelo
 *   consumo (kg/h de GLP ou m³/h de gás encanado), ou um custo fixo por hora.
 * - Tarifas (config "tarifas"): R$/kWh, preço do botijão, R$/m³ do gás encanado.
 * - Modelos salvos (coleção "gastosIndiretos"): itens com categoria para reaproveitar
 *   ("Forno 30 min", "Luz da cozinha", "Água e limpeza"…), em R$ fixo ou calculados por equipamento.
 *
 * Na receita, cada item vira um valor em R$ e a soma é o total de gastos indiretos.
 */
class Indiretos(private val banco: Banco) {

    companion object {
        val categoriasPadrao = listOf("Energia elétrica", "Gás", "Água", "Limpeza e descartáveis", "Outros")
        val fontes = linkedMapOf(
            "eletrico" to "Elétrico (potência em W)",
            "glp" to "Gás de botijão (kg/h)",
            "encanado" to "Gás encanado (m³/h)",
            "hora" to "Custo fixo por hora (R$/h)",
        )
        fun categoriaDaFonte(f: String) = when (f) { "eletrico" -> "Energia elétrica"; "glp", "encanado" -> "Gás"; else -> "Outros" }
    }

    fun tarifas(): Obj = banco.get("config", "tarifas") ?: emptyMap()

    /** R$ por kWh, por kg de GLP e por m³ (null quando o usuário ainda não informou). */
    private fun precoUnidade(fonte: String, t: Obj): Double? = when (fonte) {
        "eletrico" -> t.numOuNull("energiaKwh")?.takeIf { it > 0 }
        "glp" -> t.numOuNull("botijaoPreco")?.takeIf { it > 0 }?.let { it / t.num("botijaoKg", 13.0).let { kg -> if (kg > 0) kg else 13.0 } }
        "encanado" -> t.numOuNull("gasM3")?.takeIf { it > 0 }
        else -> 1.0
    }

    /** Consumo por hora na unidade da tarifa (kWh/h, kg/h, m³/h) ou R$/h para "hora". */
    private fun consumoHora(eq: Obj): Double? {
        val uso = eq.num("usoPct", 100.0).let { if (it <= 0) 100.0 else it } / 100.0
        return when (eq.str("fonte")) {
            "eletrico" -> eq.numOuNull("potenciaW")?.takeIf { it > 0 }?.let { it / 1000.0 * uso }
            "glp", "encanado" -> eq.numOuNull("consumo")?.takeIf { it > 0 }?.let { it * uso }
            "hora" -> eq.numOuNull("custoHora")?.takeIf { it > 0 }
            else -> null
        }
    }

    /** Custo por hora de um equipamento e, se não der para calcular, o motivo. */
    fun custoHora(eq: Obj, t: Obj = tarifas()): Pair<Double?, String?> {
        val f = eq.str("fonte")
        val c = consumoHora(eq) ?: return null to when (f) {
            "eletrico" -> "Informe a potência do equipamento (W)."
            "glp", "encanado" -> "Informe o consumo de gás do equipamento."
            else -> "Informe o custo por hora."
        }
        val p = precoUnidade(f, t) ?: return null to when (f) {
            "eletrico" -> "Informe a tarifa de energia (R$ por kWh) em Gastos indiretos."
            "glp" -> "Informe o preço do botijão de gás em Gastos indiretos."
            else -> "Informe a tarifa do gás encanado (R$ por m³) em Gastos indiretos."
        }
        return c * p to null
    }

    fun equipamentos(): List<MObj> {
        val t = tarifas()
        return banco.todos("equipamentos").map { e ->
            val (ch, aviso) = custoHora(e, t)
            (LinkedHashMap(e) as MObj).apply { this["custoHora"] = ch; this["aviso"] = aviso }
        }.sortedBy { Unidades.normalizar(it.str("nome")) }
    }

    fun modelos(): List<Obj> = banco.todos("gastosIndiretos").sortedWith(compareBy({ it.str("categoria") }, { Unidades.normalizar(it.str("nome")) }))

    /** Calcula os gastos indiretos de uma receita (lista "indiretos"). */
    fun calcular(itens: List<Obj>): MObj {
        val t = tarifas()
        val eqs = banco.todos("equipamentos").associateBy { it.str("id") }
        var total = 0.0; var completo = true
        val linhas = itens.map { i ->
            val l = obj("nome" to i.str("nome"), "categoria" to i.str("categoria", "Outros"), "modo" to i.str("modo", "manual"))
            var v: Double? = null; var aviso: String? = null
            if (i.str("modo") == "equipamento") {
                val eq = eqs[i.str("equipamentoId")]
                val min = i.num("minutos")
                when {
                    eq == null -> aviso = "Equipamento não encontrado. Escolha outro."
                    min <= 0 -> aviso = "Informe por quantos minutos o equipamento fica ligado."
                    else -> {
                        val (ch, a) = custoHora(eq, t)
                        if (ch == null) aviso = a else v = ch * min / 60.0
                        l["nome"] = i.str("nome").ifBlank { eq.str("nome") }
                        l["detalhe"] = "${Formato.numero(min)} min de ${eq.str("nome")}" + (ch?.let { " (${Formato.moeda(it)}/h)" } ?: "")
                        l["minutos"] = min; l["equipamentoId"] = eq.str("id")
                    }
                }
            } else {
                v = i.numOuNull("valor")
                if (v == null) aviso = "Informe o valor."
            }
            l["valor"] = v; l["aviso"] = aviso
            if (v == null) completo = false else total += v
            l
        }
        val porCategoria = linhas.filter { it["valor"] != null }.groupBy { it.str("categoria") }
            .map { (c, ls) -> obj("nome" to c, "valor" to ls.sumOf { it.num("valor") }) }.sortedByDescending { it.num("valor") }
        return obj("itens" to linhas, "total" to total, "completo" to completo, "porCategoria" to porCategoria)
    }
}
