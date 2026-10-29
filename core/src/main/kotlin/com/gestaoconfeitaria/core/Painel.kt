package com.gestaoconfeitaria.core

import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale

object Formato {
    private val br = Locale("pt", "BR")
    fun moeda(v: Double): String = NumberFormat.getCurrencyInstance(br).format(v).replace(' ', ' ')
    fun moedaCurta(v: Double): String = "R$ " + NumberFormat.getIntegerInstance(br).format(Math.round(v))
    fun numero(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString() else String.format(br, "%.3f", v).trimEnd('0').trimEnd(',')
    fun pct(v: Double, casas: Int = 1): String = String.format(br, "%.${casas}f%%", v * 100)
    private val meses = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    /** "2026-06" → "jun/26" */
    fun mes(ym: String): String = if (ym.length < 7) ym else "${meses[ym.substring(5, 7).toInt() - 1]}/${ym.substring(2, 4)}"
}

/** Categorias padrão e sugestão automática a partir da descrição do item. */
object Categorias {
    const val PESSOAL = "Pessoal (fora do negócio)"
    val padrao = listOf("Insumos", "Embalagens e decoração", "Utensílios e equipamentos", "Entregas", "Marketing",
        "Cursos", "Outros", PESSOAL)
    private val regras = listOf(
        "Embalagens e decoração" to Regex("\\b(CAIXA|CX|FORMINHA|FORM\\.|SACO|SACOLA|EMBALAG|FITA|ETIQUETA|BANDEJA|POTE|VELA|TOPO|LACO|FECHO|BLISTER|CESTINHA|GUARDANAPO|PAPEL|ADESIV|ACETATO|CAKEB|SAQUINHO)"),
        "Utensílios e equipamentos" to Regex("\\b(FORMA|ESPATULA|BATEDOR|FRIGIDEIRA|BICO|PANELA|ASSADEIRA|TABUA|RASPADOR|COLHER S|REGADOR|TACA|CANECA|ESTENCIL|MOLDE|BALANCA|FOUET|PENEIRA)"),
        "Entregas" to Regex("\\b(UBER|ENTREGA|FRETE|MOTOBOY|99 ?POP|LALAMOVE)"),
        "Marketing" to Regex("\\b(ANUNCIO|IMPRESS|PROPAGANDA|CARTAO VISITA|PANFLETO|INSTAGRAM|GRAFICA)"),
        "Cursos" to Regex("\\b(CURSO|AULA|WORKSHOP|MENTORIA)"),
    )
    fun sugerir(descricao: String): String {
        val d = Unidades.normalizar(descricao)
        return regras.firstOrNull { it.second.containsMatchIn(d) }?.first ?: "Insumos"
    }
}

/** Indicadores do painel ("Resumo"), no espírito da aba Análise Resumo da planilha. */
class Painel(private val banco: Banco, private val custos: Custos) {

    private data class Gasto(val mes: String, val valor: Double, val loja: String, val lojaChave: String,
                             val pagamento: String, val categoria: String, val produto: String, val notaId: String)

    fun chaveFornecedor(nome: String): String =
        Unidades.normalizar(nome).replace(Regex("[^A-Z0-9 ]"), " ")
            .replace(Regex("\\b(LTDA|ME|EPP|S ?A|EIRELI|FL ?\\d+|FILIAL ?\\d*|LOJA ?\\d+)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { "NAO INFORMADO" }

    fun calcular(filtro: Obj): MObj {
        val excluidas = (banco.get("config", "geral")?.strs("categoriasExcluidas") ?: listOf(Categorias.PESSOAL)).toSet()
        val catFiltro = filtro.str("categoria")
        val todos = ArrayList<Gasto>()
        for (n in banco.todos("notas")) {
            val mes = n.str("emissao").take(7); if (mes.length < 7) continue
            val pag = n.str("pagamento").trim().ifEmpty { "Não informado" }
            for (i in n.lista("itens")) {
                val cat = i.str("categoria").ifEmpty { Categorias.sugerir(i.str("descricao")) }
                if (cat in excluidas && catFiltro != cat) continue
                if (catFiltro.isNotEmpty() && cat != catFiltro) continue
                todos += Gasto(mes, i.numOuNull("valorAjustado") ?: i.num("valorTotal"), n.str("emitente"),
                    chaveFornecedor(n.str("emitente")), pag, cat, i.str("descricao"), n.str("id"))
            }
        }
        val vendasTodas = banco.todos("vendas").filter { it.str("data").length >= 7 }

        // Período: padrão = últimos 12 meses com dados
        val mesesComDados = (todos.map { it.mes } + vendasTodas.map { it.str("data").take(7) }).toSortedSet()
        val ate = filtro.str("ate").ifEmpty { mesesComDados.lastOrNull() ?: YearMonth.now().toString() }
        val de = filtro.str("de").ifEmpty { YearMonth.parse(ate).minusMonths(11).toString() }
        val meses = generateSequence(YearMonth.parse(de)) { it.plusMonths(1) }
            .takeWhile { it <= YearMonth.parse(ate) }.map { it.toString() }.toList()
        val noPeriodo = todos.filter { it.mes in de..ate }
        val vendas = vendasTodas.filter { it.str("data").take(7) in de..ate }

        val gastoTotal = noPeriodo.sumOf { it.valor }
        val faturamento = vendas.sumOf { it.num("valorTotal") }
        val gastoMes = meses.associateWith { m -> noPeriodo.filter { it.mes == m }.sumOf { it.valor } }
        val vendaMes = meses.associateWith { m -> vendas.filter { it.str("data").startsWith(m) }.sumOf { it.num("valorTotal") } }
        val mesesPositivos = gastoMes.filterValues { it > 0 }
        val maior = mesesPositivos.maxByOrNull { it.value }
        val menor = mesesPositivos.minByOrNull { it.value }
        val media = if (mesesPositivos.isEmpty()) 0.0 else mesesPositivos.values.average()

        // Período anterior de mesmo tamanho, para comparação
        val n = meses.size.toLong()
        val antAte = YearMonth.parse(de).minusMonths(1).toString()
        val antDe = YearMonth.parse(de).minusMonths(n).toString()
        val gastoAnterior = todos.filter { it.mes in antDe..antAte }.sumOf { it.valor }
        val fatAnterior = vendasTodas.filter { it.str("data").take(7) in antDe..antAte }.sumOf { it.num("valorTotal") }

        val fornecedores = agrupar(noPeriodo, { it.lojaChave }, { g -> g.groupingBy { it.loja.trim() }.eachCount().maxByOrNull { it.value }!!.key.ifEmpty { "Não informado" } })
        val pagamentos = agrupar(noPeriodo, { it.pagamento }, { it.first().pagamento })
        val categorias = agrupar(noPeriodo, { it.categoria }, { it.first().categoria })
        val produtos = agrupar(noPeriodo, { custos.chaveProduto(it.produto) }, { it.first().produto })
        val notas = noPeriodo.map { it.notaId }.distinct().size
        val lider = fornecedores.firstOrNull()
        val top4 = fornecedores.take(4).sumOf { it.num("valor") }
        val semPag = pagamentos.firstOrNull { it.str("nome") == "Não informado" }

        // O mês corrente ainda não terminou: a comparação "ritmo recente" usa o último mês fechado
        val mesCorrente = YearMonth.now().toString()
        val ultimo = meses.lastOrNull { (gastoMes[it] ?: 0.0) > 0 && it < mesCorrente }
        val parcial = gastoMes[mesCorrente]?.takeIf { it > 0 && mesCorrente in meses }
        val anteriorAoUltimo = ultimo?.let { YearMonth.parse(it).minusMonths(1).toString() }
        val varUltimo = if (ultimo != null && (gastoMes[anteriorAoUltimo] ?: 0.0) > 0)
            gastoMes[ultimo]!! / gastoMes[anteriorAoUltimo]!! - 1 else null

        val insumosEmAlta = custos.insumos().filter { (it.numOuNull("variacao") ?: 0.0) > 0.0001 }
            .sortedByDescending { it.num("variacao") }.take(5)
            .map { obj("id" to it["id"], "nome" to it["nome"], "variacao" to it["variacao"], "precoExib" to it["precoExib"], "unidadeExib" to it["unidadeExib"]) }
        val gastoInsumos = noPeriodo.filter { it.categoria == "Insumos" }.sumOf { it.valor }

        val kpis = obj(
            "gastoTotal" to gastoTotal, "gastoAnterior" to gastoAnterior,
            "faturamento" to faturamento, "faturamentoAnterior" to fatAnterior,
            "resultado" to faturamento - gastoTotal,
            "margem" to if (faturamento > 0) (faturamento - gastoTotal) / faturamento else null,
            "cmv" to if (faturamento > 0) gastoInsumos / faturamento else null,
            "maiorMes" to maior?.let { obj("mes" to it.key, "rotulo" to Formato.mes(it.key), "valor" to it.value) },
            "menorMes" to menor?.let { obj("mes" to it.key, "rotulo" to Formato.mes(it.key), "valor" to it.value) },
            "mediaMensal" to media,
            "fornecedorLider" to lider,
            "concentracaoTop4" to if (gastoTotal > 0) top4 / gastoTotal else 0.0,
            "notas" to notas, "ticketMedio" to if (notas > 0) gastoTotal / notas else 0.0,
            "vendas" to vendas.size, "ticketMedioVenda" to if (vendas.isNotEmpty()) faturamento / vendas.size else 0.0,
            "variacaoUltimoMes" to varUltimo?.let { obj("mes" to ultimo, "rotulo" to Formato.mes(ultimo!!), "pct" to it) },
        )

        // Principais descobertas (texto automático)
        val d = ArrayList<String>()
        if (maior != null && gastoTotal > 0 && mesesPositivos.size > 1)
            d += "Concentração mensal: ${Formato.mes(maior.key)} atingiu ${Formato.moeda(maior.value)}, ou ${Formato.pct(maior.value / gastoTotal)} do período" +
                (if (menor != null && menor.key != maior.key) "; ${Formato.mes(menor.key)} foi o menor mês (${Formato.moeda(menor.value)})." else ".")
        if (lider != null && gastoTotal > 0)
            d += "Fornecedores: ${lider.str("nome")} lidera com ${Formato.moeda(lider.num("valor"))}, equivalente a ${Formato.pct(lider.num("pct"))} do gasto total" +
                (if (fornecedores.size > 4) "; os 4 maiores concentram ${Formato.pct(top4 / gastoTotal)}." else ".")
        if (pagamentos.isNotEmpty()) {
            val p = pagamentos.filter { it.str("nome") != "Não informado" }.take(2)
            d += "Pagamentos: " + p.joinToString("; ") { "${it.str("nome").lowercase()} representa ${Formato.pct(it.num("pct"))}" } +
                (semPag?.let { ". Registros sem forma informada somam ${Formato.moeda(it.num("valor"))} (${Formato.pct(it.num("pct"))})." } ?: ".")
        }
        if (varUltimo != null && ultimo != null)
            d += "Ritmo recente: ${Formato.mes(ultimo)} ${if (varUltimo >= 0) "cresceu" else "caiu"} ${Formato.pct(Math.abs(varUltimo))} frente a ${Formato.mes(anteriorAoUltimo!!)}" +
                ", e está ${Formato.pct(Math.abs(gastoMes[ultimo]!! / media - 1))} ${if (gastoMes[ultimo]!! >= media) "acima" else "abaixo"} da média mensal de ${Formato.moeda(media)}." +
                (parcial?.let { " ${Formato.mes(mesCorrente)} está em andamento, com ${Formato.moeda(it)} até agora." } ?: "")
        if (faturamento > 0)
            d += "Resultado: faturamento de ${Formato.moeda(faturamento)} contra ${Formato.moeda(gastoTotal)} de gastos — " +
                "${if (faturamento >= gastoTotal) "lucro" else "prejuízo"} de ${Formato.moeda(Math.abs(faturamento - gastoTotal))} (margem ${Formato.pct((faturamento - gastoTotal) / faturamento)})."
        insumosEmAlta.firstOrNull()?.let {
            d += "Preço dos insumos: ${it.str("nome")} subiu ${Formato.pct(it.num("variacao"))} na última compra (agora ${Formato.moeda(it.num("precoExib"))}/${it.str("unidadeExib")})."
        }

        val semPagCount = noPeriodo.count { it.pagamento == "Não informado" }
        val qualidade = buildList {
            if (semPagCount > 0) add("$semPagCount itens sem forma de pagamento foram agrupados como \"Não informado\".")
            add("Fornecedores com pequenas variações de nome (pontuação, filial, LTDA) foram consolidados.")
            if (excluidas.isNotEmpty() && catFiltro.isEmpty()) add("Categorias fora dos cálculos: ${excluidas.joinToString()}.")
        }

        return obj(
            "de" to de, "ate" to ate, "meses" to meses.size,
            "periodo" to "${Formato.mes(de)} a ${Formato.mes(ate)}",
            "linhas" to noPeriodo.size,
            "kpis" to kpis,
            "serie" to meses.map { obj("mes" to it, "rotulo" to Formato.mes(it), "gastos" to gastoMes[it], "vendas" to vendaMes[it]) },
            "fornecedores" to fornecedores.take(8), "pagamentos" to pagamentos, "categorias" to categorias,
            "produtos" to produtos.take(10), "insumosEmAlta" to insumosEmAlta,
            "descobertas" to d, "qualidade" to qualidade,
            "mesesDisponiveis" to mesesComDados.toList(),
            "categoriasDisponiveis" to (banco.get("config", "geral")?.strs("categorias")?.ifEmpty { null } ?: Categorias.padrao),
        )
    }

    private fun agrupar(l: List<Gasto>, chave: (Gasto) -> String, nome: (List<Gasto>) -> String): List<MObj> {
        val total = l.sumOf { it.valor }
        return l.groupBy(chave).map { (k, g) ->
            val v = g.sumOf { it.valor }
            obj("chave" to k, "nome" to nome(g), "valor" to v, "pct" to if (total > 0) v / total else 0.0, "itens" to g.size)
        }.sortedByDescending { it.num("valor") }
    }
}
