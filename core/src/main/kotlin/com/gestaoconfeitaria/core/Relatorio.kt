package com.gestaoconfeitaria.core

import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Monta o Excel no mesmo formato da planilha de controle da confeitaria:
 * Análise Resumo · Resumo Mês (tabela dinâmica) · Notas_Consolidadas · Preço Médio ·
 * Conciliação Bancária · Insumos · Receitas · Vendas.
 */
class Relatorio(private val banco: Banco, private val custos: Custos, private val painel: Painel) {

    private val AZUL = "16324F"; private val AZUL2 = "1F4E78"; private val VERDE = "0F766E"
    private val CINZA = "5B6573"; private val CLARO = "F3F6F9"; private val LINHA = "DCE3E8"
    private val MOEDA = "\"R$\" #,##0.00"
    private val MOEDA0 = "\"R$\" #,##0"
    /** Cores das fatias de pizza (paleta categórica validada para daltonismo; "Outros" em cinza). */
    private val PIZZA = listOf("2A78D6", "EB6834", "1BAF7A", "EDA100", "E87BA4", "008300", "9AA5AB")

    private val titulo = Xlsx.Estilo(negrito = true, tamanho = 16, corFonte = "FFFFFF", fundo = AZUL, vertical = "center")
    private val subtitulo = Xlsx.Estilo(italico = true, corFonte = CINZA, vertical = "center")
    private val cab = Xlsx.Estilo(negrito = true, corFonte = "FFFFFF", fundo = AZUL2, vertical = "center", quebrar = true)
    private val moeda = Xlsx.Estilo(formato = MOEDA)
    private val data = Xlsx.Estilo(formato = "dd/mm/yyyy")
    private val qtd = Xlsx.Estilo(formato = "#,##0.###")

    fun gerar(filtro: Obj, out: OutputStream) {
        val x = Xlsx()
        val p = painel.calcular(filtro)
        val notas = banco.todos("notas").sortedBy { it.str("emissao") }

        analise(x.aba("Análise Resumo"), p)
        val resumo = x.aba("Resumo Mês")
        val linhasItens = consolidadas(x.aba("Notas_Consolidadas"), notas)
        resumo.texto(0, 0, "Gasto por loja e mês (Valor Total - Ajustado) — arraste os campos para analisar de outro jeito",
            Xlsx.Estilo(negrito = true, corFonte = AZUL))
        resumo.largura(0, 46.0); for (c in 1..14) resumo.largura(c, 13.0)
        if (linhasItens.isNotEmpty()) resumo.tabelaDinamica(Xlsx.Dinamica(
            nome = "ResumoMes", abaFonte = "Notas_Consolidadas", cabecalhos = COLUNAS, tipos = TIPOS, linhas = linhasItens,
            campoLinha = 7, campoColuna = 14, campoValor = 10, nomeValor = "Soma de Valor Total - Ajustado",
            legendaLinha = "Loja", legendaColuna = "Mês"))
        precoMedio(x.aba("Preço Médio"))
        conciliacao(x.aba("Conciliação Bancária"), notas)
        insumos(x.aba("Insumos"))
        receitas(x.aba("Receitas"))
        vendas(x.aba("Vendas"))
        x.escrever(out)
    }

    // ------------------------------------------------------------ Análise Resumo

    private fun analise(a: Xlsx.Aba, p: Obj) {
        a.semGrade = true
        a.paisagem = true
        a.areaImpressao = "A1:L52"
        for (c in 0..11) a.largura(c, 10.5)
        a.largura(12, 3.0)
        a.texto(0, 0, "ANÁLISE DE GASTOS — RESUMO MENSAL", titulo); a.mesclar(0, 0, 0, 11, titulo); a.altura(0, 30.0)
        val nome = banco.get("config", "geral")?.str("nomeNegocio").orEmpty()
        a.texto(1, 0, (if (nome.isNotBlank()) "$nome  |  " else "") + "Período: ${p.str("periodo")} | Fonte: Notas_Consolidadas | ${p.long("linhas")} linhas analisadas | " +
            "Gerado em ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))}", subtitulo)
        a.mesclar(1, 0, 1, 11); a.altura(1, 20.0)

        val k = p.obj("kpis")!!
        val serie = p.lista("serie")
        val forn = p.lista("fornecedores").take(6)
        val pags = p.lista("pagamentos").take(6)
        // Pizza com no máximo 6 fatias; o restante vira "Demais"
        val cats = p.lista("categorias").let { l ->
            if (l.size <= 6) l else l.take(6) + obj("nome" to "Demais", "valor" to l.drop(6).sumOf { it.num("valor") })
        }

        // Tabelas de apoio (fonte dos gráficos), como nas colunas N:V da planilha original
        val cabT = Xlsx.Estilo(negrito = true, corFonte = CINZA, borda = LINHA)
        a.texto(0, 13, "Mês", cabT); a.texto(0, 14, "Gastos", cabT); a.texto(0, 15, "Vendas", cabT)
        serie.forEachIndexed { i, s -> a.texto(i + 1, 13, s.str("rotulo")); a.num(i + 1, 14, s.num("gastos"), moeda); a.num(i + 1, 15, s.num("vendas"), moeda) }
        a.texto(0, 17, "Fornecedor", cabT); a.texto(0, 18, "Valor", cabT)
        forn.forEachIndexed { i, f -> a.texto(i + 1, 17, f.str("nome")); a.num(i + 1, 18, f.num("valor"), moeda) }
        a.texto(0, 20, "Forma de pagamento", cabT); a.texto(0, 21, "Valor", cabT); a.texto(0, 22, "%", cabT)
        pags.forEachIndexed { i, f -> a.texto(i + 1, 20, f.str("nome")); a.num(i + 1, 21, f.num("valor"), moeda); a.num(i + 1, 22, f.num("pct"), Xlsx.Estilo(formato = "0.0%")) }
        a.texto(0, 24, "Categoria", cabT); a.texto(0, 25, "Valor", cabT)
        cats.forEachIndexed { i, f -> a.texto(i + 1, 24, f.str("nome")); a.num(i + 1, 25, f.num("valor"), moeda) }
        a.largura(13, 9.0); a.largura(14, 12.0); a.largura(15, 12.0); a.largura(16, 2.0)
        a.largura(17, 36.0); a.largura(18, 12.0); a.largura(19, 2.0); a.largura(20, 28.0); a.largura(21, 12.0)
        a.largura(22, 7.0); a.largura(23, 2.0); a.largura(24, 26.0); a.largura(25, 12.0)
        val ultimaSerie = serie.size

        // Cartões de indicadores (2 fileiras de 4)
        fun cartao(linha: Int, coluna: Int, rotulo: String, preencher: (Int, Int, Xlsx.Estilo) -> Unit, detalhe: String?, cor: String) {
            val er = Xlsx.Estilo(negrito = true, corFonte = cor, fundo = CLARO, tamanho = 10)
            a.texto(linha, coluna, rotulo.uppercase(), er); a.mesclar(linha, coluna, linha, coluna + 2, er)
            val ev = Xlsx.Estilo(negrito = true, tamanho = 18, corFonte = "16324F", fundo = CLARO, vertical = "center", formato = MOEDA0)
            preencher(linha + 1, coluna, ev); a.mesclar(linha + 1, coluna, linha + 2, coluna + 2, ev)
            val ed = Xlsx.Estilo(italico = true, tamanho = 9, corFonte = CINZA, fundo = CLARO, borda = cor, quebrar = true, vertical = "top")
            a.texto(linha + 3, coluna, detalhe ?: "", ed); a.mesclar(linha + 3, coluna, linha + 3, coluna + 2, ed)
        }
        val rS = "O2:O${ultimaSerie + 1}"
        cartao(2, 0, "Gasto total", { r, c, e -> a.formula(r, c, "SUM($rS)", k.num("gastoTotal"), e) },
            comparacao(k.num("gastoTotal"), k.num("gastoAnterior")), VERDE)
        cartao(2, 3, "Maior mês", { r, c, e -> a.formula(r, c, "MAX($rS)", k.obj("maiorMes")?.num("valor") ?: 0.0, e) },
            k.obj("maiorMes")?.str("rotulo"), VERDE)
        cartao(2, 6, "Média mensal", { r, c, e -> a.formula(r, c, "AVERAGEIF($rS,\">0\")", k.num("mediaMensal"), e) },
            "meses com gasto", VERDE)
        cartao(2, 9, "Fornecedor líder", { r, c, e -> a.texto(r, c, k.obj("fornecedorLider")?.str("nome") ?: "—", e.copy(tamanho = 11, quebrar = true, formato = null)) },
            k.obj("fornecedorLider")?.let { "${Formato.moeda(it.num("valor"))} · ${Formato.pct(it.num("pct"))} do total" }, VERDE)
        val fat = k.num("faturamento")
        cartao(7, 0, "Faturamento (vendas)", { r, c, e -> a.formula(r, c, "SUM(P2:P${ultimaSerie + 1})", fat, e) },
            if (fat > 0) comparacao(fat, k.num("faturamentoAnterior")) else "lance as vendas no app", AZUL2)
        cartao(7, 3, "Resultado", { r, c, e -> a.formula(r, c, "A9-A4", fat - k.num("gastoTotal"), e) },
            k.numOuNull("margem")?.let { "margem de ${Formato.pct(it)}" } ?: "faturamento − gastos", AZUL2)
        cartao(7, 6, "Ticket médio de compra", { r, c, e -> a.num(r, c, k.num("ticketMedio"), e) }, "${k.long("notas")} notas no período", AZUL2)
        cartao(7, 9, "Concentração top 4", { r, c, e -> a.num(r, c, k.num("concentracaoTop4"), e.copy(formato = "0.0%")) },
            "fatia dos 4 maiores fornecedores", AZUL2)

        // Gráficos
        val nomes = serie.map { it.str("rotulo") }
        val temVendas = serie.any { it.num("vendas") > 0 }
        val seriesMes = mutableListOf(Triple("Gastos", Xlsx.intervalo(a.nome, 1, 14, ultimaSerie, 14), serie.map { it.num("gastos") }))
        if (temVendas) seriesMes += Triple("Vendas", Xlsx.intervalo(a.nome, 1, 15, ultimaSerie, 15), serie.map { it.num("vendas") })
        if (serie.isNotEmpty()) a.grafico(Xlsx.Grafico("Evolução mensal dos gastos", false, Xlsx.intervalo(a.nome, 1, 13, ultimaSerie, 13), nomes,
            seriesMes, listOf(VERDE, "B83D6E"), 0 to 12, 6 to 29))
        if (forn.isNotEmpty()) a.grafico(Xlsx.Grafico("Principais fornecedores", true, Xlsx.intervalo(a.nome, 1, 17, forn.size, 17), forn.map { it.str("nome") },
            listOf(Triple("Valor", Xlsx.intervalo(a.nome, 1, 18, forn.size, 18), forn.map { it.num("valor") })), listOf("2F5597"), 6 to 12, 12 to 29, MOEDA0))
        if (pags.isNotEmpty()) a.grafico(Xlsx.Grafico("Distribuição por forma de pagamento", true, Xlsx.intervalo(a.nome, 1, 20, pags.size, 20), pags.map { it.str("nome") },
            listOf(Triple("Valor", Xlsx.intervalo(a.nome, 1, 21, pags.size, 21), pags.map { it.num("valor") })), listOf("5DB7A8"), 0 to 30, 6 to 46, MOEDA0))

        // Principais descobertas (como na planilha original)
        val desc = Xlsx.Estilo(negrito = true, tamanho = 12, corFonte = AZUL)
        a.texto(30, 6, "PRINCIPAIS DESCOBERTAS", desc); a.mesclar(30, 6, 31, 11, desc)
        val et = Xlsx.Estilo(quebrar = true, vertical = "top", tamanho = 10, corFonte = "263238")
        p.strs("descobertas").take(5).forEachIndexed { i, s ->
            val r = 32 + i * 3
            a.texto(r, 6, "• $s", et); a.mesclar(r, 6, r + 2, 11, et)
        }
        // Gastos por categoria (pizza) e crescimento mês a mês (setas ▲▼)
        if (cats.isNotEmpty()) a.grafico(Xlsx.Grafico("Gastos por categoria", false, Xlsx.intervalo(a.nome, 1, 24, cats.size, 24), cats.map { it.str("nome") },
            listOf(Triple("Valor", Xlsx.intervalo(a.nome, 1, 25, cats.size, 25), cats.map { it.num("valor") })), PIZZA, 0 to 47, 6 to 64, pizza = true))
        a.texto(47, 6, "CRESCIMENTO MÊS A MÊS", desc); a.mesclar(47, 6, 47, 11, desc)
        val cabV = Xlsx.Estilo(negrito = true, corFonte = CINZA, borda = LINHA, tamanho = 10)
        listOf("Mês", "Gastos", "Var.", "Vendas", "Lucro", "Var.").forEachIndexed { i, h -> a.texto(48, 6 + i, h, cabV.copy(alinhar = if (i == 0) null else "right")) }
        val pequeno = Xlsx.Estilo(formato = MOEDA0, tamanho = 10)
        fun seta(r: Int, c: Int, atual: Double, anterior: Double?, subirEhBom: Boolean) {
            if (anterior == null || anterior == 0.0) { a.texto(r, c, "—", Xlsx.Estilo(corFonte = CINZA, alinhar = "right", tamanho = 10)); return }
            val v = (atual - anterior) / Math.abs(anterior)
            val sobe = v >= 0
            val cor = if (Math.abs(v) < 0.0005) CINZA else if (sobe == subirEhBom) VERDE else "B4372F"
            a.texto(r, c, "${if (sobe) "▲" else "▼"} ${Formato.pct(Math.abs(v), 0)}", Xlsx.Estilo(corFonte = cor, negrito = true, alinhar = "right", tamanho = 10))
        }
        serie.forEachIndexed { i, s ->
            val r = 49 + i; val ant = serie.getOrNull(i - 1)
            a.texto(r, 6, s.str("rotulo"), Xlsx.Estilo(tamanho = 10))
            a.num(r, 7, s.num("gastos"), pequeno); seta(r, 8, s.num("gastos"), ant?.num("gastos"), false)
            a.num(r, 9, s.num("vendas"), pequeno); a.num(r, 10, s.num("lucro"), pequeno)
            seta(r, 11, s.num("lucro"), ant?.num("lucro"), true)
        }
        val fim = maxOf(65, 49 + serie.size) + 1
        val q = Xlsx.Estilo(italico = true, tamanho = 9, corFonte = "6B4F00", fundo = "FFF4E5", quebrar = true, vertical = "top")
        a.texto(fim, 0, "Nota de qualidade: " + p.strs("qualidade").joinToString(" "), q); a.mesclar(fim, 0, fim + 1, 11, q)
        a.areaImpressao = "A1:L${fim + 2}"
    }

    private fun comparacao(atual: Double, anterior: Double): String =
        if (anterior <= 0) "sem período anterior para comparar"
        else "${if (atual >= anterior) "▲" else "▼"} ${Formato.pct(Math.abs(atual / anterior - 1))} vs período anterior"

    // ------------------------------------------------------------ Notas_Consolidadas (fonte da tabela dinâmica)

    private val COLUNAS = listOf("Número da Nota", "Data", "Código", "Produto", "Quantidade", "Valor Unitário", "Valor Total",
        "Loja", "CNPJ", "Forma Pagamento", "Valor Total - Ajustado", "Observações", "Categoria", "Unidade", "Mês", "Origem", "Chave de acesso")
    private val TIPOS = listOf('T', 'D', 'T', 'T', 'N', 'N', 'N', 'T', 'T', 'T', 'N', 'T', 'T', 'T', 'T', 'T', 'T')

    private fun consolidadas(a: Xlsx.Aba, notas: List<Obj>): List<List<Any?>> {
        COLUNAS.forEachIndexed { c, h -> a.texto(0, c, h, cab) }
        a.altura(0, 30.0)
        fun t(s: String) = s.trim().replace(Regex("\\s+"), " ").take(255).ifEmpty { null }
        val linhas = ArrayList<List<Any?>>()
        for (n in notas) for (i in n.lista("itens")) {
            val emissao = n.str("emissao")
            linhas += listOf(t(n.str("numero")), emissao.take(19).ifEmpty { null }, t(i.str("codigo")), t(i.str("descricao")) ?: "(sem descrição)",
                i.num("quantidade", 1.0), i.num("valorUnitario"), i.num("valorTotal"), t(n.str("emitente")) ?: "Não informado",
                t(n.str("cnpj")), t(n.str("pagamento")) ?: "Não informado", i.numOuNull("valorAjustado") ?: i.num("valorTotal"),
                t(i.str("observacao")), t(i.str("categoria")) ?: Categorias.sugerir(i.str("descricao")), t(i.str("unidade")),
                emissao.take(7).ifEmpty { "(sem data)" }, n.str("origem").ifEmpty { "manual" }, t(n.str("chave")))
        }
        linhas.forEachIndexed { k, l ->
            val r = k + 1
            l.forEachIndexed { c, v ->
                when {
                    v == null -> {}
                    TIPOS[c] == 'D' -> a.data(r, c, v as String)
                    c == 4 -> a.num(r, c, v as Double, qtd)
                    TIPOS[c] == 'N' -> a.num(r, c, v as Double, moeda)
                    else -> a.texto(r, c, v as String)
                }
            }
        }
        listOf(12.0, 11.0, 11.0, 42.0, 10.0, 12.0, 12.0, 34.0, 19.0, 22.0, 14.0, 24.0, 22.0, 8.0, 9.0, 10.0, 46.0)
            .forEachIndexed { c, w -> a.largura(c, w) }
        a.congelar(1)
        a.autoFiltro(0, 0, linhas.size, COLUNAS.size - 1)
        return linhas
    }

    // ------------------------------------------------------------ demais abas

    private fun tabela(a: Xlsx.Aba, cabecalhos: List<String>, larguras: List<Double>, linhas: List<List<Any?>>, formatos: Map<Int, Xlsx.Estilo> = emptyMap()) {
        cabecalhos.forEachIndexed { c, h -> a.texto(0, c, h, cab) }
        a.altura(0, 30.0)
        linhas.forEachIndexed { k, l ->
            l.forEachIndexed { c, v ->
                val e = formatos[c] ?: Xlsx.Estilo()
                when (v) {
                    null -> {}
                    is Double -> a.num(k + 1, c, v, e)
                    is Number -> a.num(k + 1, c, v.toDouble(), e)
                    else -> if (e.formato == "dd/mm/yyyy") a.data(k + 1, c, v.toString(), e) else a.texto(k + 1, c, v.toString(), e)
                }
            }
        }
        larguras.forEachIndexed { c, w -> a.largura(c, w) }
        a.congelar(1)
        if (linhas.isNotEmpty()) a.autoFiltro(0, 0, linhas.size, cabecalhos.size - 1)
    }

    private fun precoMedio(a: Xlsx.Aba) {
        val linhas = custos.precosPorProduto().map { p ->
            listOf(p.str("descricao"), p.num("quantidade"), p.num("valor"), p.num("precoMedio"),
                p.obj("conteudo")?.let { "${Formato.numero(it.num("valor"))} ${it.str("base")}" } ?: "não identificado",
                p.numOuNull("precoExib"), p.str("unidadeExib").ifEmpty { null }, p.str("insumo").ifEmpty { null })
        }
        tabela(a, listOf("Produto", "Soma de quantidade", "Soma de valor total", "Preço Médio Ponderado", "Conteúdo da embalagem",
            "Preço tabelado", "por", "Insumo vinculado"), listOf(46.0, 12.0, 14.0, 14.0, 16.0, 14.0, 6.0, 28.0), linhas,
            mapOf(1 to qtd, 2 to moeda, 3 to moeda, 5 to moeda))
    }

    private fun conciliacao(a: Xlsx.Aba, notas: List<Obj>) {
        val linhas = notas.map { n ->
            listOf(if (n.bool("conferido")) "☑" else "☐", n.str("emissao").take(10).ifEmpty { null }, n.str("pagamento").ifEmpty { "Não informado" },
                n.str("emitente"), n.num("valorTotal"), n.str("numero"))
        }
        tabela(a, listOf("Conferido", "Data", "Forma de pagamento", "Fornecedor / descrição", "Valor", "Nº nota"),
            listOf(10.0, 12.0, 28.0, 44.0, 14.0, 12.0), linhas, mapOf(0 to Xlsx.Estilo(alinhar = "center"), 1 to data, 4 to moeda))
    }

    private fun insumos(a: Xlsx.Aba) {
        val linhas = custos.insumos().map { i ->
            listOf(i.str("nome"), i.numOuNull("precoExib"), i.str("unidadeExib"), when (i.str("metodoPreco", "ultimo")) {
                "media" -> "Média ponderada"; "manual" -> "Manual"; else -> "Última compra" },
                i.obj("ultimaCompra")?.str("data")?.take(10), i.obj("ultimaCompra")?.str("loja"), i.numOuNull("variacao"),
                i.lista("pendencias").size.takeIf { it > 0 }?.let { "$it produto(s) sem unidade identificada" })
        }
        tabela(a, listOf("Insumo", "Preço tabelado", "por", "Método", "Última compra", "Loja", "Variação", "Pendências"),
            listOf(32.0, 14.0, 6.0, 16.0, 13.0, 32.0, 10.0, 34.0), linhas,
            mapOf(1 to moeda, 4 to data, 6 to Xlsx.Estilo(formato = "+0.0%;-0.0%;0.0%")))
    }

    private fun receitas(a: Xlsx.Aba) {
        val rs = custos.receitas()
        val cabs = listOf("Receita", "Rendimento", "Custo ingredientes", "Gastos indiretos", "Mão de obra", "Custo total", "Margem / lucro",
            "Preço de venda", "Preço por porção", "Margem sobre o preço", "Situação")
        cabs.forEachIndexed { c, h -> a.texto(0, c, h, cab) }
        a.altura(0, 30.0)
        var r = 1
        for (rec in rs) {
            val k = rec.obj("calculo")!!
            a.texto(r, 0, rec.str("nome"), Xlsx.Estilo(negrito = true))
            a.texto(r, 1, "${Formato.numero(k.num("rendimento"))} ${k.str("rendimentoUnidade")}")
            a.num(r, 2, k.num("custoIngredientes"), moeda); a.num(r, 3, k.num("gastosIndiretos"), moeda)
            a.num(r, 4, k.num("maoDeObra"), moeda); a.num(r, 5, k.num("custoTotal"), moeda); a.num(r, 6, k.num("lucro"), moeda)
            a.num(r, 7, k.num("precoVenda"), Xlsx.Estilo(formato = MOEDA, negrito = true)); a.num(r, 8, k.num("precoPorcao"), moeda)
            a.num(r, 9, k.num("margemSobrePreco"), Xlsx.Estilo(formato = "0.0%"))
            a.texto(r, 10, if (k.bool("completo")) "OK" else "Faltam preços/unidades")
            r++
        }
        r += 2
        val sub = Xlsx.Estilo(negrito = true, corFonte = AZUL)
        a.texto(r++, 0, "INGREDIENTES POR RECEITA", sub)
        listOf("Receita", "Ingrediente", "Quantidade", "Unidade", "Preço tabelado", "por", "Custo", "Aviso")
            .forEachIndexed { c, h -> a.texto(r, c, h, cab) }
        r++
        for (rec in rs) for (ing in rec.obj("calculo")!!.lista("ingredientes")) {
            a.texto(r, 0, rec.str("nome")); a.texto(r, 1, ing.str("nome"))
            a.num(r, 2, ing.num("quantidade"), qtd); a.texto(r, 3, ing.str("unidade"))
            a.num(r, 4, ing.numOuNull("precoExib"), moeda); a.texto(r, 5, ing.str("unidadeExib"))
            a.num(r, 6, ing.numOuNull("custo"), moeda); a.texto(r, 7, ing.str("aviso"))
            r++
        }
        r += 2
        a.texto(r++, 0, "GASTOS INDIRETOS POR RECEITA", sub)
        listOf("Receita", "Item", "Categoria", "Como foi calculado", "Valor", "Aviso").forEachIndexed { c, h -> a.texto(r, c, h, cab) }
        r++
        for (rec in rs) for (g in rec.obj("calculo")!!.obj("indiretos")?.lista("itens").orEmpty()) {
            a.texto(r, 0, rec.str("nome")); a.texto(r, 1, g.str("nome")); a.texto(r, 2, g.str("categoria"))
            a.texto(r, 3, g.str("detalhe").ifEmpty { "valor informado" }); a.num(r, 4, g.numOuNull("valor"), moeda); a.texto(r, 5, g.str("aviso"))
            r++
        }
        val eqs = custos.indiretos.equipamentos()
        if (eqs.isNotEmpty()) {
            r += 2
            a.texto(r++, 0, "EQUIPAMENTOS", sub)
            listOf("Equipamento", "Tipo", "Potência / consumo", "Uso da potência", "Custo por hora").forEachIndexed { c, h -> a.texto(r, c, h, cab) }
            r++
            for (e in eqs) {
                a.texto(r, 0, e.str("nome")); a.texto(r, 1, Indiretos.fontes[e.str("fonte")] ?: e.str("fonte"))
                a.texto(r, 2, when (e.str("fonte")) {
                    "eletrico" -> "${Formato.numero(e.num("potenciaW"))} W"; "glp" -> "${Formato.numero(e.num("consumo"))} kg/h"
                    "encanado" -> "${Formato.numero(e.num("consumo"))} m³/h"; else -> Formato.moeda(e.num("custoHora")) + "/h" })
                a.texto(r, 3, if (e.str("fonte") == "hora") "" else "${Formato.numero(e.num("usoPct", 100.0))}%")
                a.num(r, 4, e.numOuNull("custoHora"), moeda); r++
            }
        }
        listOf(30.0, 30.0, 22.0, 30.0, 14.0, 14.0, 14.0, 14.0, 14.0, 12.0, 40.0).forEachIndexed { c, w -> a.largura(c, w) }
        a.congelar(1)
    }

    private fun vendas(a: Xlsx.Aba) {
        val nomes = banco.todos("receitas").associate { it.str("id") to it.str("nome") }
        val linhas = banco.todos("vendas").sortedBy { it.str("data") }.map { v ->
            listOf(v.str("data").take(10).ifEmpty { null }, v.str("descricao"), nomes[v.str("receitaId")], v.num("quantidade", 1.0),
                v.num("valorUnitario"), v.num("valorTotal"), v.str("pagamento"), v.str("cliente"))
        }
        tabela(a, listOf("Data", "Descrição", "Receita", "Quantidade", "Valor unitário", "Valor total", "Pagamento", "Cliente"),
            listOf(12.0, 36.0, 26.0, 11.0, 13.0, 13.0, 22.0, 24.0), linhas, mapOf(0 to data, 3 to qtd, 4 to moeda, 5 to moeda))
    }
}
