package com.gestaoconfeitaria.core

import org.w3c.dom.Element
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/** Converte "1.234,56" / "1234.56" / "R$ 12,30" em Double. */
fun valorBr(s: String?): Double {
    if (s.isNullOrBlank()) return 0.0
    var t = s.replace(Regex("[^0-9,.-]"), "")
    if (t.contains(',')) t = t.replace(".", "").replace(',', '.')
    return t.toDoubleOrNull() ?: 0.0
}

/** "28/10/2026 14:22:31" → "2026-10-28T14:22:31" */
fun dataBrIso(s: String?): String {
    if (s.isNullOrBlank()) return ""
    val m = Regex("(\\d{2})/(\\d{2})/(\\d{4})(?:\\D+(\\d{2}):(\\d{2})(?::(\\d{2}))?)?").find(s) ?: return ""
    val (d, mo, y, h, mi, se) = m.destructured
    return "$y-$mo-${d}T${h.ifEmpty { "00" }}:${mi.ifEmpty { "00" }}:${se.ifEmpty { "00" }}"
}

/** Informações embutidas na chave de acesso de 44 dígitos. */
object ChaveAcesso {
    private val UF = mapOf(
        "11" to "RO", "12" to "AC", "13" to "AM", "14" to "RR", "15" to "PA", "16" to "AP", "17" to "TO",
        "21" to "MA", "22" to "PI", "23" to "CE", "24" to "RN", "25" to "PB", "26" to "PE", "27" to "AL",
        "28" to "SE", "29" to "BA", "31" to "MG", "32" to "ES", "33" to "RJ", "35" to "SP", "41" to "PR",
        "42" to "SC", "43" to "RS", "50" to "MS", "51" to "MT", "52" to "GO", "53" to "DF",
    )
    /** Página de "consulta pela chave de acesso" de cada SEFAZ (impressa no cupom; o usuário pode corrigir). */
    val urlConsulta = mapOf(
        "AC" to "http://www.sefaznet.ac.gov.br/nfce/consulta", "AL" to "http://www.sefaz.al.gov.br/nfce/consulta",
        "AM" to "http://www.sefaz.am.gov.br/nfce/consulta", "AP" to "http://www.sefaz.ap.gov.br/nfce/consulta",
        "BA" to "http://www.sefaz.ba.gov.br/nfce/consulta", "CE" to "http://www.sefaz.ce.gov.br/nfce/consulta",
        "DF" to "http://www.fazenda.df.gov.br/nfce/consulta", "ES" to "http://www.sefaz.es.gov.br/nfce/consulta",
        "GO" to "http://www.sefaz.go.gov.br/nfce/consulta", "MA" to "http://www.sefaz.ma.gov.br/nfce/consulta",
        "MS" to "http://www.dfe.ms.gov.br/nfce/consulta", "MT" to "http://www.sefaz.mt.gov.br/nfce/consultanfce",
        "MG" to "http://nfce.fazenda.mg.gov.br/portalnfce", "PA" to "http://www.sefa.pa.gov.br/nfce/consulta",
        "PB" to "http://www.receita.pb.gov.br/nfce/consulta", "PE" to "http://nfce.sefaz.pe.gov.br/nfce/consulta",
        "PI" to "http://www.sefaz.pi.gov.br/nfce/consulta", "PR" to "http://www.fazenda.pr.gov.br/nfce/consulta",
        "RJ" to "http://www.fazenda.rj.gov.br/nfce/consulta", "RN" to "http://www.set.rn.gov.br/nfce/consulta",
        "RO" to "http://www.sefin.ro.gov.br/nfce/consulta", "RR" to "http://www.sefaz.rr.gov.br/nfce/consulta",
        "RS" to "http://www.sefaz.rs.gov.br/nfce/consulta", "SC" to "https://sat.sef.sc.gov.br/nfce/consulta",
        "SP" to "https://www.nfce.fazenda.sp.gov.br/consulta", "SE" to "http://www.nfce.se.gov.br/nfce/consulta",
        "TO" to "http://www.sefaz.to.gov.br/nfce/consulta",
    )

    fun extrair(texto: String): String? = Regex("(?<!\\d)\\d{44}(?!\\d)").find(texto.replace(Regex("[\\s.]"), ""))?.value

    fun valida(ch: String): Boolean {
        if (!Regex("\\d{44}").matches(ch)) return false
        var peso = 2; var soma = 0
        for (i in 42 downTo 0) { soma += (ch[i] - '0') * peso; peso = if (peso == 9) 2 else peso + 1 }
        val dv = 11 - soma % 11
        return (if (dv >= 10) 0 else dv) == ch[43] - '0'
    }

    fun info(ch: String): MObj {
        val uf = UF[ch.take(2)] ?: ""
        val c = ch.substring(6, 20)
        return obj(
            "chave" to ch, "valida" to valida(ch), "uf" to uf,
            "anoMes" to "20${ch.substring(2, 4)}-${ch.substring(4, 6)}",
            "cnpj" to "${c.substring(0, 2)}.${c.substring(2, 5)}.${c.substring(5, 8)}/${c.substring(8, 12)}-${c.substring(12)}",
            "modelo" to ch.substring(20, 22),
            "serie" to ch.substring(22, 25).trimStart('0').ifEmpty { "0" },
            "numero" to ch.substring(25, 34).trimStart('0').ifEmpty { "0" },
            "urlConsulta" to urlConsulta[uf],
        )
    }
}

object Importadores {

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Distribui o desconto da nota proporcionalmente entre os itens ("Valor Total - Ajustado"). */
    private fun ratear(itens: List<MObj>, totalPago: Double) {
        val bruto = itens.sumOf { it.num("valorTotal") }
        val fator = if (totalPago > 0 && bruto > 0 && totalPago < bruto) totalPago / bruto else 1.0
        itens.forEach { if (it["valorAjustado"] == null) it["valorAjustado"] = Math.round(it.num("valorTotal") * fator * 100) / 100.0 }
    }

    // ------------------------------------------------------------ página da SEFAZ (dados do extrator.js)

    fun deSefaz(o: Obj, url: String, chaveQr: String?): MObj {
        val itens = o.lista("itens").map { i ->
            val qtd = valorBr(i.str("quantidade")).let { if (it == 0.0) 1.0 else it }
            val total = valorBr(i.str("valorTotal"))
            val unit = valorBr(i.str("valorUnitario")).let { if (it == 0.0) total / qtd else it }
            obj("codigo" to i.str("codigo").trim(), "descricao" to i.str("descricao").trim(), "quantidade" to qtd,
                "unidade" to i.str("unidade").trim().uppercase(), "valorUnitario" to unit, "valorTotal" to total,
                "categoria" to Categorias.sugerir(i.str("descricao")))
        }
        val chave = o.str("chave").takeIf { it.length == 44 } ?: chaveQr ?: ""
        val info = if (chave.length == 44) ChaveAcesso.info(chave) else null
        val cnpj = o.str("cnpj").ifBlank { info?.str("cnpj") ?: "" }
        val total = valorBr(o.str("total")).takeIf { it > 0 } ?: itens.sumOf { it.num("valorTotal") }
        ratear(itens, total)
        val emissao = dataBrIso(o.str("emissao")).ifEmpty { info?.let { it.str("anoMes") + "-01T00:00:00" } ?: "" }
        return obj(
            "id" to (if (chave.isNotEmpty()) chave else "pg-" + hash(url)),
            "origem" to "sefaz", "chave" to chave,
            "numero" to o.str("numero").ifBlank { info?.str("numero") ?: "" },
            "serie" to o.str("serie").ifBlank { info?.str("serie") ?: "" },
            "emissao" to emissao, "emitente" to o.str("emitente").ifBlank { "CNPJ $cnpj" }, "cnpj" to cnpj,
            "endereco" to o.str("endereco"), "pagamento" to normalizarPagamento(o.str("pagamento")),
            "valorTotal" to total, "desconto" to Math.max(0.0, itens.sumOf { it.num("valorTotal") } - total),
            "url" to url, "conferido" to false, "itens" to itens,
        )
    }

    fun normalizarPagamento(s: String): String {
        val n = Unidades.normalizar(s)
        return when {
            n.isBlank() -> ""
            "CREDITO" in n -> "Cartão de Crédito"
            "DEBITO" in n -> "Cartão de Débito"
            "PIX" in n -> "Pix"
            "DINHEIRO" in n -> "Dinheiro"
            "TRANSF" in n || "CARTEIRA" in n -> "Transferência/Carteira Digital"
            "VALE" in n -> "Vale"
            "BOLETO" in n -> "Boleto"
            else -> s.trim()
        }
    }

    // ------------------------------------------------------------ XML da NF-e / NFC-e

    private val tPag = mapOf("01" to "Dinheiro", "02" to "Cheque", "03" to "Cartão de Crédito", "04" to "Cartão de Débito",
        "05" to "Crédito Loja", "10" to "Vale", "11" to "Vale", "12" to "Vale", "13" to "Vale", "15" to "Boleto",
        "16" to "Depósito", "17" to "Pix", "18" to "Transferência/Carteira Digital", "19" to "Fidelidade",
        "90" to "Sem pagamento", "99" to "Outros")

    fun deXml(xml: String): MObj {
        val f = DocumentBuilderFactory.newInstance()
        // Proteção contra XML malicioso (entidades externas)
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        f.isExpandEntityReferences = false
        f.isNamespaceAware = false
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
        val inf = doc.getElementsByTagName("infNFe").item(0) as? Element
            ?: throw IllegalArgumentException("Este arquivo não é um XML de NF-e/NFC-e (não tem <infNFe>).")
        fun Element.filho(nome: String): Element? = getElementsByTagName(nome).item(0) as? Element
        fun Element?.txt(nome: String): String = this?.filho(nome)?.textContent?.trim() ?: ""
        fun num(s: String) = s.toDoubleOrNull() ?: 0.0

        val chave = inf.getAttribute("Id").filter { it.isDigit() }.takeIf { it.length == 44 }
            ?: (doc.getElementsByTagName("chNFe").item(0)?.textContent?.trim() ?: "")
        val ide = inf.filho("ide"); val emit = inf.filho("emit"); val end = emit?.filho("enderEmit")
        val tot = inf.filho("ICMSTot")
        val dets = inf.getElementsByTagName("det")
        val itens = (0 until dets.length).map { k ->
            val p = (dets.item(k) as Element).filho("prod")
            val vProd = num(p.txt("vProd"))
            val ajust = vProd - num(p.txt("vDesc")) + num(p.txt("vFrete")) + num(p.txt("vSeg")) + num(p.txt("vOutro"))
            obj("codigo" to p.txt("cProd"), "descricao" to p.txt("xProd"), "quantidade" to num(p.txt("qCom")),
                "unidade" to p.txt("uCom").uppercase(), "valorUnitario" to num(p.txt("vUnCom")), "valorTotal" to vProd,
                "valorAjustado" to Math.round(ajust * 100) / 100.0, "categoria" to Categorias.sugerir(p.txt("xProd")))
        }
        val pags = inf.getElementsByTagName("detPag")
        val pagamento = (0 until pags.length).map { pags.item(it) as Element }
            .maxByOrNull { num(it.txt("vPag")) }?.let { tPag[it.txt("tPag")] } ?: ""
        val cnpjBruto = emit.txt("CNPJ").ifEmpty { emit.txt("CPF") }
        val cnpj = if (cnpjBruto.length == 14)
            "${cnpjBruto.substring(0, 2)}.${cnpjBruto.substring(2, 5)}.${cnpjBruto.substring(5, 8)}/${cnpjBruto.substring(8, 12)}-${cnpjBruto.substring(12)}"
            else cnpjBruto
        val dh = ide.txt("dhEmi").ifEmpty { ide.txt("dEmi") }
        return obj(
            "id" to chave.ifEmpty { "xml-" + hash(xml) }, "origem" to "xml", "chave" to chave,
            "numero" to ide.txt("nNF"), "serie" to ide.txt("serie"),
            "emissao" to (if (dh.length >= 19) dh.substring(0, 19) else if (dh.length == 10) dh + "T00:00:00" else ""),
            "emitente" to emit.txt("xNome").ifEmpty { emit.txt("xFant") }, "cnpj" to cnpj,
            "endereco" to listOf(end.txt("xLgr"), end.txt("nro"), end.txt("xBairro"), end.txt("xMun"), end.txt("UF"))
                .filter { it.isNotBlank() }.joinToString(", "),
            "pagamento" to pagamento, "valorTotal" to (num(tot.txt("vNF")).takeIf { it > 0 } ?: itens.sumOf { it.num("valorAjustado") }),
            "desconto" to num(tot.txt("vDesc")), "url" to "", "conferido" to false, "itens" to itens,
        )
    }

    // ------------------------------------------------------------ planilha antiga (.xlsx/.xlsm)

    private val cabecalhos = mapOf(
        "numero" to listOf("NUMERO DA NOTA", "N NOTA", "NO NOTA", "NUMERO", "NOTA"),
        "data" to listOf("DATA", "EMISSAO"),
        "codigo" to listOf("CODIGO"),
        "produto" to listOf("PRODUTO", "DESCRICAO"),
        "quantidade" to listOf("QUANTIDADE", "QTDE", "QTD"),
        "unidade" to listOf("UNIDADE", "UN"),
        "valorUnitario" to listOf("VALOR UNITARIO", "VL UNIT", "PRECO UNITARIO"),
        "valorTotal" to listOf("VALOR TOTAL"),
        "ajustado" to listOf("VALOR TOTAL - AJUSTADO", "VALOR TOTAL AJUSTADO", "VALOR AJUSTADO"),
        "loja" to listOf("LOJA", "ESTABELECIMENTO", "FORNECEDOR", "EMITENTE"),
        "cnpj" to listOf("CNPJ"),
        "pagamento" to listOf("FORMA PAGAMENTO", "FORMA DE PAGAMENTO", "PAGAMENTO"),
        "obs" to listOf("OBSERVACOES", "OBSERVACAO", "OBS"),
        "categoria" to listOf("CATEGORIA"),
    )

    /**
     * Lê uma planilha no formato da aba Notas_Consolidadas (ou a aba Itens exportada pelo app)
     * e devolve as notas agrupadas por número + data + loja.
     */
    fun dePlanilha(bytes: ByteArray): List<MObj> {
        val partes = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) partes[e.name.removePrefix("/")] = z.readBytes()
                e = z.nextEntry
            }
        }
        val wb = partes["xl/workbook.xml"]?.toString(Charsets.UTF_8) ?: throw IllegalArgumentException("Arquivo não é uma planilha do Excel.")
        val rels = partes["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8) ?: ""
        val alvos = Regex("<Relationship\\b[^>]*>").findAll(rels).associate { m ->
            val t = m.value
            Regex("Id=\"([^\"]+)\"").find(t)!!.groupValues[1] to Regex("Target=\"([^\"]+)\"").find(t)!!.groupValues[1]
        }
        val abas = Regex("<sheet\\b[^>]*>").findAll(wb).map { m ->
            val nome = Regex("name=\"([^\"]+)\"").find(m.value)!!.groupValues[1].replace("&amp;", "&")
            val rid = Regex("r:id=\"([^\"]+)\"").find(m.value)!!.groupValues[1]
            nome to ("xl/" + alvos[rid]!!.removePrefix("/xl/").removePrefix("../"))
        }.toList()
        val compartilhadas = partes["xl/sharedStrings.xml"]?.let { lerCompartilhadas(it) } ?: emptyList()

        val ordem = abas.sortedByDescending { (n, _) -> if (Unidades.normalizar(n).contains("CONSOLIDAD")) 2 else if (n == "Itens") 1 else 0 }
        for ((_, caminho) in ordem) {
            val linhas = lerAba(partes[caminho] ?: continue, compartilhadas)
            val r = interpretar(linhas)
            if (r != null) return r
        }
        throw IllegalArgumentException("Não encontrei uma aba com as colunas Produto e Valor Total.")
    }

    private fun interpretar(linhas: List<Map<Int, Any>>): List<MObj>? {
        val iCab = linhas.indexOfFirst { l -> l.values.any { norm(it) == "PRODUTO" || norm(it) == "DESCRICAO" } && l.values.any { norm(it).startsWith("VALOR TOTAL") } }
        if (iCab < 0) return null
        val cab = linhas[iCab]
        val col = HashMap<String, Int>()
        for ((campo, nomes) in cabecalhos) for (nome in nomes) {
            val c = cab.entries.firstOrNull { norm(it.value) == nome && it.key !in col.values }?.key ?: continue
            col[campo] = c; break
        }
        fun texto(l: Map<Int, Any>, campo: String): String = col[campo]?.let { l[it] }?.let {
            if (it is Double && it == Math.floor(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString()
        }?.trim() ?: ""
        fun numero(l: Map<Int, Any>, campo: String): Double? = col[campo]?.let { l[it] }?.let { if (it is Double) it else valorBr(it.toString()).takeIf { v -> v != 0.0 } }
        fun data(l: Map<Int, Any>): String = col["data"]?.let { l[it] }?.let { v ->
            when (v) {
                is Double -> LocalDate.of(1899, 12, 30).plusDays(v.toLong()).toString()
                else -> dataBrIso(v.toString()).take(10).ifEmpty { Regex("\\d{4}-\\d{2}-\\d{2}").find(v.toString())?.value ?: "" }
            }
        } ?: ""

        val grupos = LinkedHashMap<String, MObj>()
        for (l in linhas.drop(iCab + 1)) {
            val prod = texto(l, "produto"); val total = numero(l, "valorTotal")
            if (prod.isEmpty() || total == null) continue
            val dt = data(l); val loja = texto(l, "loja"); val num = texto(l, "numero")
            val chaveGrupo = "$num|$dt|${Unidades.normalizar(loja)}"
            val nota = grupos.getOrPut(chaveGrupo) {
                obj("id" to "pl-" + hash(chaveGrupo), "origem" to "planilha", "chave" to "", "numero" to num, "serie" to "",
                    "emissao" to if (dt.isNotEmpty()) dt + "T00:00:00" else "", "emitente" to loja, "cnpj" to texto(l, "cnpj"),
                    "endereco" to "", "pagamento" to normalizarPagamento(texto(l, "pagamento")), "valorTotal" to 0.0,
                    "desconto" to 0.0, "url" to "", "conferido" to false, "itens" to ArrayList<MObj>())
            }
            val qtd = numero(l, "quantidade") ?: 1.0
            val item = obj("codigo" to texto(l, "codigo"), "descricao" to prod, "quantidade" to qtd,
                "unidade" to texto(l, "unidade").uppercase(), "valorUnitario" to (numero(l, "valorUnitario") ?: total / qtd),
                "valorTotal" to total, "valorAjustado" to (numero(l, "ajustado") ?: total),
                "categoria" to texto(l, "categoria").ifEmpty { Categorias.sugerir(prod) }, "observacao" to texto(l, "obs"))
            @Suppress("UNCHECKED_CAST") (nota["itens"] as MutableList<MObj>) += item
            nota["valorTotal"] = nota.num("valorTotal") + item.num("valorAjustado")
            if (nota.str("pagamento").isEmpty()) nota["pagamento"] = normalizarPagamento(texto(l, "pagamento"))
        }
        grupos.values.forEach { n ->
            n["valorTotal"] = Math.round(n.num("valorTotal") * 100) / 100.0
            n["desconto"] = Math.max(0.0, Math.round((n.lista("itens").sumOf { it.num("valorTotal") } - n.num("valorTotal")) * 100) / 100.0)
        }
        return grupos.values.toList()
    }

    private fun norm(v: Any?) = Unidades.normalizar(v?.toString() ?: "").replace(Regex("[^A-Z0-9 -]"), "").replace(Regex("\\s+"), " ").trim()

    private fun sax() = SAXParserFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newSAXParser()

    private fun lerCompartilhadas(xml: ByteArray): List<String> {
        val r = ArrayList<String>()
        val sb = StringBuilder(); var emT = false; var emFonetica = false
        sax().parse(ByteArrayInputStream(xml), object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes?) {
                when (q.substringAfter(':')) { "si" -> sb.setLength(0); "t" -> emT = true; "rPh" -> emFonetica = true }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q.substringAfter(':')) { "si" -> r += sb.toString(); "t" -> emT = false; "rPh" -> emFonetica = false }
            }
            override fun characters(ch: CharArray, s: Int, n: Int) { if (emT && !emFonetica) sb.append(ch, s, n) }
        })
        return r
    }

    /** Linhas da aba como mapa coluna → valor (String ou Double). Lê só as 40 primeiras colunas. */
    private fun lerAba(xml: ByteArray, compartilhadas: List<String>): List<Map<Int, Any>> {
        val linhas = ArrayList<Map<Int, Any>>()
        var linha = HashMap<Int, Any>()
        var col = -1; var tipo = ""; val sb = StringBuilder(); var lendo = false
        sax().parse(ByteArrayInputStream(xml), object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (q.substringAfter(':')) {
                    "row" -> linha = HashMap()
                    "c" -> {
                        val ref = a.getValue("r") ?: ""
                        col = ref.takeWhile { it.isLetter() }.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
                        tipo = a.getValue("t") ?: ""
                        sb.setLength(0)
                    }
                    "v", "t" -> lendo = true
                }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q.substringAfter(':')) {
                    "v", "t" -> lendo = false
                    "c" -> if (col in 0..39 && sb.isNotEmpty()) {
                        val s = sb.toString()
                        linha[col] = when (tipo) {
                            "s" -> compartilhadas.getOrElse(s.trim().toIntOrNull() ?: -1) { "" }
                            "str", "inlineStr", "e" -> s
                            "b" -> s
                            else -> s.toDoubleOrNull() ?: s
                        }
                    }
                    "row" -> if (linha.isNotEmpty()) linhas += linha
                }
            }
            override fun characters(ch: CharArray, s: Int, n: Int) { if (lendo) sb.append(ch, s, n) }
        })
        return linhas
    }
}
