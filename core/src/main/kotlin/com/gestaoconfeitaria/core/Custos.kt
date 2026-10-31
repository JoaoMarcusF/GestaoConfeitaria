package com.gestaoconfeitaria.core

import com.gestaoconfeitaria.core.Unidades.Conteudo
import com.gestaoconfeitaria.core.Unidades.Medida

/**
 * Insumos (ingredientes/embalagens com preço tabelado) e custo das receitas.
 *
 * Um insumo junta as compras de um ou mais produtos das notas (vinculos = descrições normalizadas).
 * O preço é sempre guardado por unidade base (R$/g, R$/ml ou R$/un) e mostrado por kg, L ou un.
 */
class Custos(private val banco: Banco) {

    val indiretos = Indiretos(banco)

    /** Chave que identifica um mesmo produto em notas diferentes. */
    fun chaveProduto(descricao: String) = Unidades.normalizar(descricao).replace(Regex("\\s+"), " ")

    data class Compra(
        val notaId: String, val data: String, val loja: String, val descricao: String, val chave: String,
        val quantidade: Double, val unidade: String, val valor: Double,
        val conteudo: Conteudo?, val qtdBase: Double?, val precoBase: Double?, val codigo: String = "",
    )

    /** Todas as compras (itens de nota) agrupadas pela chave do produto. */
    private fun comprasPorProduto(conteudosManuais: Map<String, Obj> = emptyMap()): Map<String, List<Compra>> {
        val r = HashMap<String, MutableList<Compra>>()
        for (n in banco.todos("notas")) for (i in n.lista("itens")) {
            val desc = i.str("descricao"); if (desc.isBlank()) continue
            val ch = chaveProduto(desc)
            val qtd = i.num("quantidade", 1.0).let { if (it <= 0) 1.0 else it }
            val valor = i.numOuNull("valorAjustado") ?: i.num("valorTotal")
            val manual = conteudosManuais[ch]?.let { Conteudo(Medida(it.num("valor"), it.str("base")), "manual", "") }
            val c = manual ?: Unidades.detectar(desc, i.str("unidade"), qtd)
            val qb = c?.let { qtd * it.porUnidade.valor }
            r.getOrPut(ch) { ArrayList() } += Compra(n.str("id"), n.str("emissao"), n.str("emitente"), desc, ch, qtd,
                i.str("unidade"), valor, c, qb, if (qb != null && qb > 0) valor / qb else null, i.str("codigo").trim())
        }
        r.values.forEach { l -> l.sortBy { it.data } }
        return r
    }

    // ------------------------------------------------------------ produtos (para escolher ao montar receita)

    fun produtos(busca: String): List<Obj> {
        val vinculados = HashMap<String, String>()
        val manuais = HashMap<String, Obj>()
        for (ins in banco.todos("insumos")) {
            ins.strs("vinculos").forEach { vinculados[it] = ins.str("id") }
            ins.obj("conteudos")?.forEach { (k, v) -> @Suppress("UNCHECKED_CAST") manuais[k] = v as Obj }
        }
        val q = Unidades.normalizar(busca).split(' ').filter { it.isNotBlank() }
        return comprasPorProduto(manuais).values.mapNotNull { compras ->
            val ult = compras.last()
            if (q.isNotEmpty() && q.any { !ult.chave.contains(it) }) return@mapNotNull null
            obj(
                "chave" to ult.chave, "descricao" to ult.descricao, "compras" to compras.size,
                "ultimaData" to ult.data, "ultimaLoja" to ult.loja, "unidade" to ult.unidade,
                "ultimoValor" to ult.valor, "ultimaQuantidade" to ult.quantidade,
                "conteudo" to ult.conteudo?.let { medidaObj(it.porUnidade) + ("fonte" to it.fonte) + ("trecho" to it.trecho) },
                "sugestao" to (if (ult.conteudo == null) Unidades.sugestao(ult.descricao)?.let { medidaObj(it) } else null),
                "precoExib" to ult.precoBase?.let { it * Unidades.fatorExibicao(ult.conteudo!!.porUnidade.base) },
                "unidadeExib" to ult.conteudo?.let { Unidades.nomeBase(it.porUnidade.base) },
                "insumoId" to vinculados[ult.chave],
            )
        }.sortedByDescending { it.str("ultimaData") }.take(200)
    }

    private fun medidaObj(m: Medida): MObj = obj("valor" to m.valor, "base" to m.base)

    /** Aba "Preço Médio": soma de quantidade, soma de valor e preço médio ponderado por produto. */
    fun precosPorProduto(): List<MObj> {
        val vinculados = HashMap<String, String>()
        val manuais = HashMap<String, Obj>()
        for (ins in banco.todos("insumos")) {
            ins.strs("vinculos").forEach { vinculados[it] = ins.str("nome") }
            ins.obj("conteudos")?.forEach { (k, v) -> @Suppress("UNCHECKED_CAST") manuais[k] = v as Obj }
        }
        return comprasPorProduto(manuais).values.map { l ->
            val q = l.sumOf { it.quantidade }; val v = l.sumOf { it.valor }
            val comBase = l.filter { it.qtdBase != null && it.qtdBase > 0 }
            val base = l.last().conteudo?.porUnidade?.base
            val pb = if (comBase.isNotEmpty()) comBase.sumOf { it.valor } / comBase.sumOf { it.qtdBase!! } else null
            obj("descricao" to l.last().descricao, "quantidade" to q, "valor" to v, "precoMedio" to if (q > 0) v / q else 0.0,
                "conteudo" to l.last().conteudo?.let { medidaObj(it.porUnidade) },
                "precoExib" to if (pb != null && base != null) pb * Unidades.fatorExibicao(base) else null,
                "unidadeExib" to (base?.let { Unidades.nomeBase(it) } ?: ""), "insumo" to (vinculados[l.last().chave] ?: ""))
        }.sortedBy { it.str("descricao").lowercase() }
    }

    // ------------------------------------------------------------ insumos

    /**
     * Cria um insumo a partir de um produto das notas (ou vincula a um insumo existente).
     * [conteudo] é o que o usuário informou quando a unidade não foi detectada.
     */
    fun criarOuVincular(chave: String, nome: String, insumoId: String?, conteudo: Obj?): Obj {
        val manuais = if (conteudo != null) mapOf(chave to conteudo) else emptyMap()
        val compra = comprasPorProduto(manuais)[chave]?.lastOrNull()
        val base = conteudo?.str("base")?.ifEmpty { null } ?: compra?.conteudo?.porUnidade?.base ?: "g"
        val atual = insumoId?.let { banco.get("insumos", it) }
        val dados: MObj = (atual?.let { LinkedHashMap(it) } ?: obj(
            "nome" to nome.ifBlank { compra?.descricao ?: chave }, "base" to base, "metodoPreco" to "ultimo",
            "categoria" to "Insumos",
        ))
        dados["vinculos"] = (dados.strs("vinculos") + chave).distinct()
        if (conteudo != null) {
            @Suppress("UNCHECKED_CAST")
            val cs = LinkedHashMap(dados.obj("conteudos") ?: emptyMap())
            cs[chave] = obj("valor" to conteudo.num("valor"), "base" to conteudo.str("base"))
            dados["conteudos"] = cs
        }
        return banco.salvar("insumos", dados)
    }

    /** Resumo de um insumo com preço calculado, histórico e pendências de unidade. */
    fun resumoInsumo(ins: Obj, todas: Map<String, List<Compra>>? = null): MObj {
        @Suppress("UNCHECKED_CAST")
        val manuais = (ins.obj("conteudos") ?: emptyMap()).mapValues { it.value as Obj }
        val porProduto = todas ?: comprasPorProduto(manuais)
        val base = ins.str("base", "g")
        val eq = Unidades.Equivalencias(ins.numOuNull("gPorMl"), ins.numOuNull("gPorUn"))
        val compras = ArrayList<MObj>()
        val pendencias = ArrayList<MObj>()
        var somaValor = 0.0; var somaQtd = 0.0
        var ultimo: MObj? = null; var penultimo: MObj? = null
        for (ch in ins.strs("vinculos")) {
            val lista = porProduto[ch] ?: continue
            for (c0 in lista) {
                val c = manuais[ch]?.let { m ->
                    val ct = Conteudo(Medida(m.num("valor"), m.str("base")), "manual", "")
                    val qb = c0.quantidade * ct.porUnidade.valor
                    c0.copy(conteudo = ct, qtdBase = qb, precoBase = if (qb > 0) c0.valor / qb else null)
                } ?: c0
                val qb = c.conteudo?.let { Unidades.paraBaseDoInsumo(Medida(c.qtdBase!!, it.porUnidade.base), base, eq) }
                val linha = obj("notaId" to c.notaId, "data" to c.data, "loja" to c.loja, "descricao" to c.descricao, "codigo" to c.codigo,
                    "quantidade" to c.quantidade, "unidade" to c.unidade, "valor" to c.valor,
                    "qtdBase" to qb, "precoExib" to qb?.let { if (it > 0) c.valor / it * Unidades.fatorExibicao(base) else null })
                compras += linha
                if (qb == null || qb <= 0) {
                    if (pendencias.none { it.str("chave") == ch }) pendencias += obj(
                        "chave" to ch, "descricao" to c.descricao,
                        "motivo" to if (c.conteudo == null) "unidade" else "equivalencia",
                        "baseCompra" to c.conteudo?.porUnidade?.base,
                        "sugestao" to Unidades.sugestao(c.descricao)?.let { medidaObj(it) })
                    continue
                }
                somaValor += c.valor; somaQtd += qb
                if (ultimo == null || c.data >= ultimo.str("data")) { penultimo = ultimo; ultimo = linha }
                else if (penultimo == null || c.data >= penultimo.str("data")) penultimo = linha
            }
        }
        compras.sortByDescending { it.str("data") }
        val fator = Unidades.fatorExibicao(base)
        val media = if (somaQtd > 0) somaValor / somaQtd else null
        val precoUlt = ultimo?.numOuNull("precoExib")?.div(fator)
        val metodo = ins.str("metodoPreco", "ultimo")
        val manual = ins.numOuNull("precoManual")?.div(fator)       // precoManual é guardado por kg / L / un
        val preco = when (metodo) { "manual" -> manual; "media" -> media; else -> precoUlt } ?: manual ?: media
        val ant = penultimo?.numOuNull("precoExib")
        return (LinkedHashMap(ins) as MObj).apply {
            this["precoBase"] = preco
            this["precoExib"] = preco?.times(fator)
            this["unidadeExib"] = Unidades.nomeBase(base)
            this["mediaExib"] = media?.times(fator)
            this["ultimoExib"] = precoUlt?.times(fator)
            this["variacao"] = if (ant != null && ant > 0 && precoUlt != null) (precoUlt * fator / ant - 1) else null
            this["ultimaCompra"] = ultimo
            this["compras"] = compras.take(50)
            this["pendencias"] = pendencias
            this["porLoja"] = rankingLojas(compras)
            // Código de barras (EAN) mais recente, para buscar em comparadores de preço
            this["ean"] = compras.map { it.str("codigo") }.firstOrNull { ehEan(it) }
        }
    }

    /** Onde cada loja vendeu mais barato (preço por kg/L/un), da menor última compra para a maior. */
    private fun rankingLojas(compras: List<MObj>): List<MObj> =
        compras.filter { it.numOuNull("precoExib") != null }.groupBy { it.str("loja").trim().ifEmpty { "Loja não informada" } }
            .map { (loja, l) ->
                val ult = l.maxByOrNull { it.str("data") }!!
                obj("loja" to loja, "ultimoPreco" to ult.num("precoExib"), "ultimaData" to ult.str("data"),
                    "menorPreco" to l.minOf { it.num("precoExib") }, "compras" to l.size)
            }.sortedBy { it.num("ultimoPreco") }

    private fun ehEan(c: String): Boolean {
        if (!Regex("\\d{8}|\\d{12,14}").matches(c)) return false
        val d = c.map { it - '0' }
        val soma = d.dropLast(1).reversed().mapIndexed { i, x -> if (i % 2 == 0) x * 3 else x }.sum()
        return (10 - soma % 10) % 10 == d.last()
    }

    fun insumos(): List<MObj> {
        val manuais = HashMap<String, Obj>()
        val lista = banco.todos("insumos")
        lista.forEach { ins -> ins.obj("conteudos")?.forEach { (k, v) -> @Suppress("UNCHECKED_CAST") manuais[k] = v as Obj } }
        val todas = comprasPorProduto(manuais)
        return lista.map { resumoInsumo(it, todas) }.sortedBy { Unidades.normalizar(it.str("nome")) }
    }

    // ------------------------------------------------------------ receitas

    /**
     * Calcula o custo de uma receita (pode ser uma receita ainda não salva, para a prévia na tela).
     *
     * Ordem: custo dos ingredientes → + mão de obra → + margem de lucro.
     * Cada etapa é "aditivo" (soma R$) ou "multiplicativo" (aplica %), à escolha do usuário.
     */
    fun calcularReceita(r: Obj, insumosResumo: Map<String, Obj>? = null): MObj {
        val mapa = insumosResumo ?: insumos().associateBy { it.str("id") }
        val linhas = ArrayList<MObj>()
        var custo = 0.0; var completo = true
        for (ing in r.lista("ingredientes")) {
            val qtd = ing.num("quantidade"); val un = ing.str("unidade", "g")
            val linha = obj("nome" to ing.str("nome"), "quantidade" to qtd, "unidade" to un, "insumoId" to ing.str("insumoId"))
            val medidaReceita = Unidades.converter(qtd, un)
            var c: Double? = null; var aviso: String? = null; var tipo: String? = null
            val ins = ing.str("insumoId").takeIf { it.isNotEmpty() }?.let { mapa[it] }
            when {
                medidaReceita == null -> { aviso = "Unidade \"$un\" não reconhecida."; tipo = "unidade_receita" }
                ins != null -> {
                    linha["nome"] = ing.str("nome").ifBlank { ins.str("nome") }
                    val p = ins.numOuNull("precoBase")
                    val base = ins.str("base", "g")
                    val eq = Unidades.Equivalencias(ins.numOuNull("gPorMl"), ins.numOuNull("gPorUn"))
                    val q = Unidades.paraBaseDoInsumo(medidaReceita, base, eq)
                    linha["precoExib"] = ins["precoExib"]; linha["unidadeExib"] = ins["unidadeExib"]
                    when {
                        p == null && ins.lista("pendencias").isNotEmpty() -> {
                            aviso = "Não identifiquei a unidade de medida na nota de \"${ins.lista("pendencias")[0].str("descricao")}\". Informe quanto vem na embalagem."
                            tipo = "unidade_compra"
                        }
                        p == null -> { aviso = "Insumo sem preço. Informe um preço manual."; tipo = "sem_preco" }
                        q == null -> {
                            aviso = "A receita usa ${medidaReceita.base} e o insumo é comprado em ${Unidades.nomeBase(base)}. " +
                                "Informe a equivalência (${if (medidaReceita.base == "un" || base == "un") "peso de 1 unidade" else "gramas por ml"})."
                            tipo = "equivalencia"
                        }
                        else -> c = q * p
                    }
                }
                ing.obj("precoManual") != null -> {
                    val pm = ing.obj("precoManual")!!
                    val ref = Unidades.converter(pm.num("quantidade", 1.0), pm.str("unidade", un))
                    when {
                        ref == null || ref.valor <= 0 -> { aviso = "Unidade do preço manual não reconhecida."; tipo = "unidade_receita" }
                        else -> {
                            val pb = pm.num("valor") / ref.valor
                            val q = Unidades.paraBaseDoInsumo(medidaReceita, ref.base,
                                Unidades.Equivalencias(ing.numOuNull("gPorMl"), ing.numOuNull("gPorUn")))
                            if (q == null) {
                                aviso = "O preço está em ${Unidades.nomeBase(ref.base)} e a receita em ${medidaReceita.base}. " +
                                    "Informe a equivalência (${if (medidaReceita.base == "un" || ref.base == "un") "peso de 1 unidade" else "gramas por ml"})."
                                tipo = "equivalencia"
                            } else c = q * pb
                            linha["precoExib"] = pb * Unidades.fatorExibicao(ref.base); linha["unidadeExib"] = Unidades.nomeBase(ref.base)
                        }
                    }
                }
                else -> { aviso = "Sem preço: escolha um insumo das notas ou informe o preço."; tipo = "sem_preco" }
            }
            linha["custo"] = c; linha["aviso"] = aviso; linha["tipoAviso"] = tipo
            if (c == null) completo = false else custo += c
            linhas += linha
        }
        // Ordem: ingredientes + gastos indiretos (R$) + mão de obra (R$ ou % sobre os ingredientes) = custo total;
        // custo total + margem (R$ ou % sobre o custo total) = preço de venda.
        val ind = indiretos.calcular(r.lista("indiretos"))
        if (!ind.bool("completo")) completo = false
        val custoIndireto = ind.num("total")
        val mo = r.obj("maoDeObra"); val mg = r.obj("margem")
        val maoDeObra = aplicar(custo, mo) - custo
        val custoTotal = custo + custoIndireto + maoDeObra
        val preco = aplicar(custoTotal, mg)
        val rend = r.num("rendimento", 1.0).let { if (it <= 0) 1.0 else it }
        return obj(
            "ingredientes" to linhas, "completo" to completo,
            "custoIngredientes" to custo, "gastosIndiretos" to custoIndireto, "indiretos" to ind,
            "maoDeObra" to maoDeObra, "custoTotal" to custoTotal,
            "lucro" to preco - custoTotal, "precoVenda" to preco,
            "margemSobrePreco" to if (preco > 0) (preco - custoTotal) / preco else 0.0,
            "precoPorcao" to preco / rend, "custoPorcao" to custoTotal / rend,
            "rendimento" to rend, "rendimentoUnidade" to r.str("rendimentoUnidade", "porções"),
        )
    }

    private fun aplicar(base: Double, ajuste: Obj?): Double {
        if (ajuste == null) return base
        val v = ajuste.num("valor")
        return if (ajuste.str("modo") == "multiplicativo") base * (1 + v / 100.0) else base + v
    }

    fun receitas(): List<MObj> {
        val mapa = insumos().associateBy { it.str("id") }
        return banco.todos("receitas").map { r -> (LinkedHashMap(r) as MObj).also { it["calculo"] = calcularReceita(r, mapa) } }
            .sortedBy { Unidades.normalizar(it.str("nome")) }
    }
}

private operator fun MObj.plus(p: Pair<String, Any?>): MObj = also { it[p.first] = p.second }
