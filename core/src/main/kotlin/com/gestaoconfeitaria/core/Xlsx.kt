package com.gestaoconfeitaria.core

import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gerador de .xlsx sem bibliotecas (só java.util.zip), com estilos, células mescladas,
 * fórmulas, gráficos de barras nativos do Excel e tabela dinâmica.
 */
class Xlsx {

    data class Estilo(
        val negrito: Boolean = false, val italico: Boolean = false, val tamanho: Int = 11, val corFonte: String? = null,
        val fundo: String? = null, val formato: String? = null, val alinhar: String? = null, val vertical: String? = null,
        val quebrar: Boolean = false, val borda: String? = null,   // borda: cor da linha fina inferior
    )

    internal sealed class Celula(val estilo: Int) {
        class Txt(val v: String, e: Int) : Celula(e)
        class Num(val v: Double, e: Int) : Celula(e)
        class Form(val f: String, val cache: Double?, e: Int) : Celula(e)
        class Vazia(e: Int) : Celula(e)
    }

    class Grafico(
        val titulo: String, val horizontal: Boolean,
        val categorias: String, val nomesCategorias: List<String>,
        val series: List<Triple<String, String, List<Double>>>,   // nome, referência, valores
        val cores: List<String>, val de: Pair<Int, Int>, val ate: Pair<Int, Int>,  // (coluna, linha) base 0
        val formatoRotulo: String? = null, val formatoEixo: String = "#,##0",
        val pizza: Boolean = false,   // gráfico de pizza (usa só a primeira série; uma cor por fatia)
    )

    /** Tabela dinâmica simples: uma linha, uma coluna e um valor somado. */
    class Dinamica(
        val nome: String, val abaFonte: String, val cabecalhos: List<String>, val tipos: List<Char>, // T texto, N número, D data
        val linhas: List<List<Any?>>, val campoLinha: Int, val campoColuna: Int, val campoValor: Int,
        val nomeValor: String, val legendaLinha: String, val legendaColuna: String, val linhaInicio: Int = 3,
    )

    inner class Aba(val nome: String) {
        internal val celulas = TreeMap<Int, TreeMap<Int, Celula>>()
        internal val mescladas = ArrayList<String>()
        internal val larguras = TreeMap<Int, Double>()
        internal val alturas = HashMap<Int, Double>()
        internal val graficos = ArrayList<Grafico>()
        internal var congelarLinhas = 0
        internal var filtro: String? = null
        internal var dinamica: Dinamica? = null
        var semGrade = false
        /** Impressão em paisagem, cabendo na largura da folha. */
        var paisagem = false
        /** Área de impressão, ex.: "A1:L50". */
        var areaImpressao: String? = null

        private fun put(r: Int, c: Int, cel: Celula) { celulas.getOrPut(r) { TreeMap() }[c] = cel }
        fun texto(r: Int, c: Int, v: String?, e: Estilo = Estilo()) {
            if (v.isNullOrEmpty()) put(r, c, Celula.Vazia(estilo(e))) else put(r, c, Celula.Txt(v, estilo(e)))
        }
        fun num(r: Int, c: Int, v: Double?, e: Estilo = Estilo()) {
            if (v == null || v.isNaN() || v.isInfinite()) put(r, c, Celula.Vazia(estilo(e))) else put(r, c, Celula.Num(v, estilo(e)))
        }
        fun data(r: Int, c: Int, iso: String?, e: Estilo = Estilo(formato = "dd/mm/yyyy")) =
            num(r, c, iso?.let { serialData(it) }, e)
        fun formula(r: Int, c: Int, f: String, cache: Double?, e: Estilo = Estilo()) = put(r, c, Celula.Form(f, cache, estilo(e)))
        fun estilizar(r: Int, c: Int, e: Estilo) { if (celulas[r]?.get(c) == null) put(r, c, Celula.Vazia(estilo(e))) }
        /** Mescla e aplica o estilo em todas as células (para bordas e fundo ficarem inteiros). */
        fun mesclar(r1: Int, c1: Int, r2: Int, c2: Int, e: Estilo? = null) {
            mescladas += "${ref(r1, c1)}:${ref(r2, c2)}"
            if (e != null) for (r in r1..r2) for (c in c1..c2) if (r != r1 || c != c1) estilizar(r, c, e)
        }
        fun largura(c: Int, w: Double) { larguras[c] = w }
        fun altura(r: Int, h: Double) { alturas[r] = h }
        fun congelar(linhas: Int) { congelarLinhas = linhas }
        fun autoFiltro(r1: Int, c1: Int, r2: Int, c2: Int) { filtro = "${ref(r1, c1)}:${ref(r2, c2)}" }
        fun grafico(g: Grafico) { graficos += g }
        fun tabelaDinamica(d: Dinamica) { dinamica = d }
    }

    private val abas = ArrayList<Aba>()
    private val estilos = LinkedHashMap<Estilo, Int>().also { it[Estilo()] = 0 }

    fun aba(nome: String) = Aba(nome).also { abas += it }

    private fun estilo(e: Estilo) = estilos.getOrPut(e) { estilos.size }

    // ====================================================================== escrita

    fun escrever(out: OutputStream) {
        // Dinâmicas precisam de estilos registrados antes de styles.xml
        ZipOutputStream(out).use { zip ->
            val conteudo = StringBuilder()
            fun put(nome: String, xml: String) { zip.putNextEntry(ZipEntry(nome)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            fun override(parte: String, tipo: String) = conteudo.append("""<Override PartName="/$parte" ContentType="$tipo"/>""")

            val relsWb = StringBuilder(REL_HEAD)
            var nGrafico = 0; var nDesenho = 0; var nDin = 0
            val caches = StringBuilder()
            abas.forEachIndexed { i, aba ->
                val n = i + 1
                relsWb.append(rel("rId$n", "worksheet", "worksheets/sheet$n.xml"))
                override("xl/worksheets/sheet$n.xml", "$SML.worksheet+xml")
                val relsAba = StringBuilder()
                var desenhoRid: String? = null
                if (aba.graficos.isNotEmpty()) {
                    nDesenho++
                    desenhoRid = "rIdDesenho"
                    relsAba.append(rel(desenhoRid, "drawing", "../drawings/drawing$nDesenho.xml"))
                    val relsDes = StringBuilder(REL_HEAD)
                    val des = StringBuilder(XML + """<xdr:wsDr xmlns:xdr="$NS_XDR" xmlns:a="$NS_A">""")
                    aba.graficos.forEachIndexed { k, g ->
                        nGrafico++
                        relsDes.append(rel("rId${k + 1}", "chart", "../charts/chart$nGrafico.xml"))
                        des.append(ancora(g, k + 1))
                        put("xl/charts/chart$nGrafico.xml", grafico(g))
                        override("xl/charts/chart$nGrafico.xml", "application/vnd.openxmlformats-officedocument.drawingml.chart+xml")
                    }
                    des.append("</xdr:wsDr>")
                    put("xl/drawings/drawing$nDesenho.xml", des.toString())
                    put("xl/drawings/_rels/drawing$nDesenho.xml.rels", relsDes.append(REL_TAIL).toString())
                    override("xl/drawings/drawing$nDesenho.xml", "application/vnd.openxmlformats-officedocument.drawing+xml")
                }
                aba.dinamica?.let { d ->
                    nDin++
                    val cache = CacheDin(d)
                    relsAba.append(rel("rIdDin", "pivotTable", "../pivotTables/pivotTable$nDin.xml"))
                    put("xl/pivotTables/pivotTable$nDin.xml", pivotTable(cache, nDin))
                    put("xl/pivotTables/_rels/pivotTable$nDin.xml.rels",
                        REL_HEAD + rel("rId1", "pivotCacheDefinition", "../pivotCache/pivotCacheDefinition$nDin.xml") + REL_TAIL)
                    put("xl/pivotCache/pivotCacheDefinition$nDin.xml", cacheDef(cache))
                    put("xl/pivotCache/_rels/pivotCacheDefinition$nDin.xml.rels",
                        REL_HEAD + rel("rId1", "pivotCacheRecords", "pivotCacheRecords$nDin.xml") + REL_TAIL)
                    put("xl/pivotCache/pivotCacheRecords$nDin.xml", cacheRecords(cache))
                    override("xl/pivotTables/pivotTable$nDin.xml", "$SML.pivotTable+xml")
                    override("xl/pivotCache/pivotCacheDefinition$nDin.xml", "$SML.pivotCacheDefinition+xml")
                    override("xl/pivotCache/pivotCacheRecords$nDin.xml", "$SML.pivotCacheRecords+xml")
                    relsWb.append(rel("rIdPC$nDin", "pivotCacheDefinition", "pivotCache/pivotCacheDefinition$nDin.xml"))
                    caches.append("""<pivotCache cacheId="$nDin" r:id="rIdPC$nDin"/>""")
                    // valores já calculados na própria aba (outros leitores mostram estes; o Excel recalcula)
                    escreverResultadoDinamica(aba, cache)
                }
                put("xl/worksheets/sheet$n.xml", planilha(aba, desenhoRid, i == 0))
                if (relsAba.isNotEmpty()) put("xl/worksheets/_rels/sheet$n.xml.rels", REL_HEAD + relsAba + REL_TAIL)
            }
            relsWb.append(rel("rIdEstilos", "styles", "styles.xml")).append(REL_TAIL)
            put("xl/workbook.xml", workbook(caches.toString()))
            put("xl/_rels/workbook.xml.rels", relsWb.toString())
            put("xl/styles.xml", estilosXml())
            put("_rels/.rels", REL_HEAD + rel("rId1", "officeDocument", "xl/workbook.xml") + REL_TAIL)
            put("[Content_Types].xml", XML + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" ContentType="$SML.sheet.main+xml"/>""" +
                """<Override PartName="/xl/styles.xml" ContentType="$SML.styles+xml"/>""" + conteudo + "</Types>")
        }
    }

    private fun workbook(caches: String): String {
        val sb = StringBuilder(XML + """<workbook xmlns="$NS_MAIN" xmlns:r="$NS_REL"><bookViews><workbookView activeTab="0"/></bookViews><sheets>""")
        abas.forEachIndexed { i, a -> sb.append("""<sheet name="${esc(a.nome)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        sb.append("</sheets>")
        val filtros = abas.mapIndexedNotNull { i, a ->
            a.filtro?.let { f -> """<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">${esc(nomeRef(a.nome))}!${absoluto(f)}</definedName>""" }
        }
        val areas = abas.mapIndexedNotNull { i, a ->
            a.areaImpressao?.let { f -> """<definedName name="_xlnm.Print_Area" localSheetId="$i">${esc(nomeRef(a.nome))}!${absoluto(f)}</definedName>""" }
        }
        if (filtros.isNotEmpty() || areas.isNotEmpty()) sb.append("<definedNames>").append((filtros + areas).joinToString("")).append("</definedNames>")
        sb.append("""<calcPr calcId="191029" fullCalcOnLoad="1"/>""")
        if (caches.isNotEmpty()) sb.append("<pivotCaches>$caches</pivotCaches>")
        return sb.append("</workbook>").toString()
    }

    private fun planilha(a: Aba, desenhoRid: String?, selecionada: Boolean): String {
        val sb = StringBuilder(XML + """<worksheet xmlns="$NS_MAIN" xmlns:r="$NS_REL">""")
        if (a.paisagem) sb.append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"""")
        if (selecionada) sb.append(""" tabSelected="1"""")
        if (a.semGrade) sb.append(""" showGridLines="0"""")
        sb.append(">")
        if (a.congelarLinhas > 0)
            sb.append("""<pane ySplit="${a.congelarLinhas}" topLeftCell="A${a.congelarLinhas + 1}" activePane="bottomLeft" state="frozen"/>""")
        sb.append("""</sheetView></sheetViews><sheetFormatPr defaultRowHeight="15"/>""")
        if (a.larguras.isNotEmpty()) {
            sb.append("<cols>")
            a.larguras.forEach { (c, w) -> sb.append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""") }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        for ((r, linha) in a.celulas) {
            sb.append("""<row r="${r + 1}"""")
            a.alturas[r]?.let { sb.append(""" ht="$it" customHeight="1"""") }
            sb.append(">")
            for ((c, cel) in linha) {
                val ref = ref(r, c)
                val s = if (cel.estilo > 0) """ s="${cel.estilo}"""" else ""
                when (cel) {
                    is Celula.Txt -> sb.append("""<c r="$ref" t="inlineStr"$s><is><t xml:space="preserve">${esc(cel.v)}</t></is></c>""")
                    is Celula.Num -> sb.append("""<c r="$ref"$s><v>${num(cel.v)}</v></c>""")
                    is Celula.Form -> sb.append("""<c r="$ref"$s><f>${esc(cel.f)}</f>${cel.cache?.let { "<v>${num(it)}</v>" } ?: ""}</c>""")
                    is Celula.Vazia -> sb.append("""<c r="$ref"$s/>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        a.filtro?.let { sb.append("""<autoFilter ref="$it"/>""") }
        if (a.mescladas.isNotEmpty()) {
            sb.append("""<mergeCells count="${a.mescladas.size}">""")
            a.mescladas.forEach { sb.append("""<mergeCell ref="$it"/>""") }
            sb.append("</mergeCells>")
        }
        sb.append("""<pageMargins left="0.5" right="0.5" top="0.6" bottom="0.6" header="0.3" footer="0.3"/>""")
        if (a.paisagem) sb.append("""<pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/>""")
        if (desenhoRid != null) sb.append("""<drawing r:id="$desenhoRid"/>""")
        return sb.append("</worksheet>").toString()
    }

    // ====================================================================== estilos

    private fun estilosXml(): String {
        val fmts = LinkedHashMap<String, Int>()
        val fontes = LinkedHashMap<String, Int>()
        val fundos = LinkedHashMap<String, Int>()
        val bordas = LinkedHashMap<String, Int>()
        fontes["""<font><sz val="11"/><name val="Calibri"/><family val="2"/></font>"""] = 0
        fundos["""<fill><patternFill patternType="none"/></fill>"""] = 0
        fundos["""<fill><patternFill patternType="gray125"/></fill>"""] = 1
        bordas["<border><left/><right/><top/><bottom/><diagonal/></border>"] = 0
        val xfs = StringBuilder()
        for (e in estilos.keys) {
            val fmtId = e.formato?.let { f -> embutidos[f] ?: fmts.getOrPut(f) { 164 + fmts.size } } ?: 0
            val fonte = buildString {
                append("<font>"); if (e.negrito) append("<b/>"); if (e.italico) append("<i/>")
                append("""<sz val="${e.tamanho}"/>"""); e.corFonte?.let { append("""<color rgb="FF$it"/>""") }
                append("""<name val="Calibri"/><family val="2"/></font>""")
            }
            val fontId = fontes.getOrPut(fonte) { fontes.size }
            val fillId = e.fundo?.let { fundos.getOrPut("""<fill><patternFill patternType="solid"><fgColor rgb="FF$it"/><bgColor indexed="64"/></patternFill></fill>""") { fundos.size } } ?: 0
            val borderId = e.borda?.let { bordas.getOrPut("""<border><left/><right/><top/><bottom style="thin"><color rgb="FF$it"/></bottom><diagonal/></border>""") { bordas.size } } ?: 0
            xfs.append("""<xf numFmtId="$fmtId" fontId="$fontId" fillId="$fillId" borderId="$borderId" xfId="0"""")
            if (fmtId > 0) xfs.append(""" applyNumberFormat="1"""")
            if (fontId > 0) xfs.append(""" applyFont="1"""")
            if (fillId > 0) xfs.append(""" applyFill="1"""")
            if (borderId > 0) xfs.append(""" applyBorder="1"""")
            if (e.alinhar != null || e.vertical != null || e.quebrar) {
                xfs.append(""" applyAlignment="1"><alignment""")
                e.alinhar?.let { xfs.append(""" horizontal="$it"""") }
                e.vertical?.let { xfs.append(""" vertical="$it"""") }
                if (e.quebrar) xfs.append(""" wrapText="1"""")
                xfs.append("/></xf>")
            } else xfs.append("/>")
        }
        val sb = StringBuilder(XML + """<styleSheet xmlns="$NS_MAIN">""")
        if (fmts.isNotEmpty()) {
            sb.append("""<numFmts count="${fmts.size}">""")
            fmts.forEach { (f, id) -> sb.append("""<numFmt numFmtId="$id" formatCode="${esc(f)}"/>""") }
            sb.append("</numFmts>")
        }
        sb.append("""<fonts count="${fontes.size}">${fontes.keys.joinToString("")}</fonts>""")
        sb.append("""<fills count="${fundos.size}">${fundos.keys.joinToString("")}</fills>""")
        sb.append("""<borders count="${bordas.size}">${bordas.keys.joinToString("")}</borders>""")
        sb.append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
        sb.append("""<cellXfs count="${estilos.size}">$xfs</cellXfs>""")
        sb.append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles><dxfs count="0"/>""")
        sb.append("""<tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/></styleSheet>""")
        return sb.toString()
    }

    private val embutidos = mapOf("0" to 1, "0.00" to 2, "#,##0" to 3, "#,##0.00" to 4, "0%" to 9, "0.00%" to 10)

    // ====================================================================== gráficos

    private fun ancora(g: Grafico, id: Int) = """<xdr:twoCellAnchor editAs="oneCell">""" +
        """<xdr:from><xdr:col>${g.de.first}</xdr:col><xdr:colOff>76200</xdr:colOff><xdr:row>${g.de.second}</xdr:row><xdr:rowOff>76200</xdr:rowOff></xdr:from>""" +
        """<xdr:to><xdr:col>${g.ate.first}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${g.ate.second}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>""" +
        """<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="${id + 1}" name="Gráfico $id"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>""" +
        """<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="$NS_C">""" +
        """<c:chart xmlns:c="$NS_C" xmlns:r="$NS_REL" r:id="rId$id"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>"""

    private fun titulo(t: String) = """<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="1200" b="1"><a:solidFill><a:srgbClr val="263238"/></a:solidFill></a:defRPr></a:pPr>""" +
        """<a:r><a:rPr lang="pt-BR" sz="1200" b="1"><a:solidFill><a:srgbClr val="263238"/></a:solidFill></a:rPr><a:t>${esc(t)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>"""

    private fun pizza(g: Grafico): String {
        val (_, refValores, valores) = g.series.first()
        val texto = """<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="900"><a:solidFill><a:srgbClr val="263238"/></a:solidFill></a:defRPr></a:pPr><a:endParaRPr lang="pt-BR"/></a:p></c:txPr>"""
        val sb = StringBuilder(XML + """<c:chartSpace xmlns:c="$NS_C" xmlns:a="$NS_A" xmlns:r="$NS_REL"><c:roundedCorners val="0"/><c:chart>""")
        sb.append(titulo(g.titulo)).append("""<c:autoTitleDeleted val="0"/><c:plotArea><c:layout/><c:pieChart><c:varyColors val="1"/>""")
        sb.append("""<c:ser><c:idx val="0"/><c:order val="0"/><c:tx><c:v>${esc(g.series.first().first)}</c:v></c:tx>""")
        valores.indices.forEach { i ->
            // Fatias com borda branca de 2 pt (separa as cores, inclusive para daltônicos)
            sb.append("""<c:dPt><c:idx val="$i"/><c:bubble3D val="0"/><c:spPr><a:solidFill><a:srgbClr val="${g.cores[i % g.cores.size]}"/></a:solidFill><a:ln w="25400"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:ln></c:spPr></c:dPt>""")
        }
        sb.append("""<c:dLbls><c:numFmt formatCode="0%" sourceLinked="0"/><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>$texto<c:dLblPos val="bestFit"/>""")
        sb.append("""<c:showLegendKey val="0"/><c:showVal val="0"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="1"/><c:showBubbleSize val="0"/><c:showLeaderLines val="1"/></c:dLbls>""")
        sb.append("""<c:cat><c:strRef><c:f>${esc(g.categorias)}</c:f><c:strCache><c:ptCount val="${g.nomesCategorias.size}"/>""")
        g.nomesCategorias.forEachIndexed { k, s -> sb.append("""<c:pt idx="$k"><c:v>${esc(s)}</c:v></c:pt>""") }
        sb.append("""</c:strCache></c:strRef></c:cat><c:val><c:numRef><c:f>${esc(refValores)}</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="${valores.size}"/>""")
        valores.forEachIndexed { k, v -> sb.append("""<c:pt idx="$k"><c:v>${num(v)}</c:v></c:pt>""") }
        sb.append("""</c:numCache></c:numRef></c:val></c:ser><c:firstSliceAng val="0"/></c:pieChart></c:plotArea>""")
        sb.append("""<c:legend><c:legendPos val="r"/><c:overlay val="0"/>${texto.replace("263238", "455A64")}</c:legend><c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart>""")
        sb.append("""<c:spPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln w="9525"><a:solidFill><a:srgbClr val="DCE3E8"/></a:solidFill></a:ln></c:spPr></c:chartSpace>""")
        return sb.toString()
    }

    private fun grafico(g: Grafico): String {
        if (g.pizza) return pizza(g)
        val sb = StringBuilder(XML + """<c:chartSpace xmlns:c="$NS_C" xmlns:a="$NS_A" xmlns:r="$NS_REL"><c:roundedCorners val="0"/><c:chart>""")
        sb.append("""<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="1200" b="1"><a:solidFill><a:srgbClr val="263238"/></a:solidFill></a:defRPr></a:pPr>""")
        sb.append("""<a:r><a:rPr lang="pt-BR" sz="1200" b="1"><a:solidFill><a:srgbClr val="263238"/></a:solidFill></a:rPr><a:t>${esc(g.titulo)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>""")
        sb.append("""<c:autoTitleDeleted val="0"/><c:plotArea><c:layout/><c:barChart><c:barDir val="${if (g.horizontal) "bar" else "col"}"/><c:grouping val="clustered"/><c:varyColors val="0"/>""")
        val texto = """<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz="900"><a:solidFill><a:srgbClr val="455A64"/></a:solidFill></a:defRPr></a:pPr><a:endParaRPr lang="pt-BR"/></a:p></c:txPr>"""
        g.series.forEachIndexed { i, (nome, refValores, valores) ->
            sb.append("""<c:ser><c:idx val="$i"/><c:order val="$i"/><c:tx><c:v>${esc(nome)}</c:v></c:tx>""")
            sb.append("""<c:spPr><a:solidFill><a:srgbClr val="${g.cores[i % g.cores.size]}"/></a:solidFill></c:spPr><c:invertIfNegative val="0"/>""")
            g.formatoRotulo?.let {
                sb.append("""<c:dLbls><c:numFmt formatCode="${esc(it)}" sourceLinked="0"/><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>$texto""")
                sb.append("""<c:showLegendKey val="0"/><c:showVal val="1"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="0"/><c:showBubbleSize val="0"/></c:dLbls>""")
            }
            sb.append("""<c:cat><c:strRef><c:f>${esc(g.categorias)}</c:f><c:strCache><c:ptCount val="${g.nomesCategorias.size}"/>""")
            g.nomesCategorias.forEachIndexed { k, s -> sb.append("""<c:pt idx="$k"><c:v>${esc(s)}</c:v></c:pt>""") }
            sb.append("""</c:strCache></c:strRef></c:cat><c:val><c:numRef><c:f>${esc(refValores)}</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="${valores.size}"/>""")
            valores.forEachIndexed { k, v -> sb.append("""<c:pt idx="$k"><c:v>${num(v)}</c:v></c:pt>""") }
            sb.append("</c:numCache></c:numRef></c:val></c:ser>")
        }
        sb.append("""<c:gapWidth val="${if (g.horizontal) 50 else 70}"/><c:axId val="5001"/><c:axId val="5002"/></c:barChart>""")
        val semLinha = """<c:spPr><a:ln><a:noFill/></a:ln></c:spPr>"""
        sb.append("""<c:catAx><c:axId val="5001"/><c:scaling><c:orientation val="${if (g.horizontal) "maxMin" else "minMax"}"/></c:scaling><c:delete val="0"/>""")
        sb.append("""<c:axPos val="${if (g.horizontal) "l" else "b"}"/><c:numFmt formatCode="General" sourceLinked="0"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/>""")
        sb.append("""$semLinha$texto<c:crossAx val="5002"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/></c:catAx>""")
        sb.append("""<c:valAx><c:axId val="5002"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="${if (g.horizontal) 1 else 0}"/><c:axPos val="${if (g.horizontal) "b" else "l"}"/>""")
        sb.append("""<c:majorGridlines><c:spPr><a:ln w="6350"><a:solidFill><a:srgbClr val="E3E8EC"/></a:solidFill></a:ln></c:spPr></c:majorGridlines>""")
        sb.append("""<c:numFmt formatCode="${esc(g.formatoEixo)}" sourceLinked="0"/><c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/>$semLinha$texto""")
        sb.append("""<c:crossAx val="5001"/><c:crosses val="${if (g.horizontal) "max" else "autoZero"}"/><c:crossBetween val="between"/></c:valAx></c:plotArea>""")
        if (g.series.size > 1) sb.append("""<c:legend><c:legendPos val="t"/><c:overlay val="0"/>$texto</c:legend>""")
        sb.append("""<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart>""")
        sb.append("""<c:spPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln w="9525"><a:solidFill><a:srgbClr val="DCE3E8"/></a:solidFill></a:ln></c:spPr></c:chartSpace>""")
        return sb.toString()
    }

    // ====================================================================== tabela dinâmica

    private inner class CacheDin(val d: Dinamica) {
        val itens = HashMap<Int, List<String?>>()        // null = célula vazia (<m/>)
        val indice = HashMap<Int, Map<String, Int>>()
        init {
            d.tipos.forEachIndexed { c, t ->
                if (t != 'T') return@forEachIndexed
                val distintos = LinkedHashMap<String, String?>()
                d.linhas.map { (it[c] as String?)?.take(255) }.sortedWith(compareBy(nullsLast()) { it?.lowercase() })
                    .forEach { v -> distintos.putIfAbsent(v?.lowercase() ?: "\u0000vazio", v) }
                itens[c] = distintos.values.toList()
                indice[c] = distintos.keys.withIndex().associate { (i, k) -> k to i }
            }
        }
        fun idx(c: Int, v: String?) = indice[c]!![v?.take(255)?.lowercase() ?: "\u0000vazio"]!!
        val rotLinhas get() = itens[d.campoLinha]!!
        val rotColunas get() = itens[d.campoColuna]!!
        val soma: Array<DoubleArray> by lazy {
            Array(rotLinhas.size) { DoubleArray(rotColunas.size) }.also { m ->
                d.linhas.forEach { l ->
                    m[idx(d.campoLinha, l[d.campoLinha] as String?)][idx(d.campoColuna, l[d.campoColuna] as String?)] +=
                        (l[d.campoValor] as Double?) ?: 0.0
                }
            }
        }
    }

    private fun cacheDef(c: CacheDin): String {
        val d = c.d
        val sb = StringBuilder(XML + """<pivotCacheDefinition xmlns="$NS_MAIN" xmlns:r="$NS_REL" r:id="rId1" refreshOnLoad="1" """ +
            """refreshedBy="Gestão Confeitaria" refreshedDate="${num(serial(LocalDateTime.now()))}" createdVersion="6" refreshedVersion="6" """ +
            """minRefreshableVersion="3" recordCount="${d.linhas.size}"><cacheSource type="worksheet">""" +
            """<worksheetSource ref="A1:${col(d.cabecalhos.size - 1)}${d.linhas.size + 1}" sheet="${esc(d.abaFonte)}"/></cacheSource>""" +
            """<cacheFields count="${d.cabecalhos.size}">""")
        d.cabecalhos.forEachIndexed { i, nome ->
            when (d.tipos[i]) {
                'T' -> {
                    val its = c.itens[i]!!
                    sb.append("""<cacheField name="${esc(nome)}" numFmtId="0"><sharedItems""")
                    if (its.contains(null)) sb.append(""" containsBlank="1"""")
                    sb.append(""" count="${its.size}">""")
                    its.forEach { sb.append(if (it == null) "<m/>" else """<s v="${esc(it)}"/>""") }
                    sb.append("</sharedItems></cacheField>")
                }
                'N' -> {
                    val vs = d.linhas.mapNotNull { it[i] as Double? }
                    sb.append("""<cacheField name="${esc(nome)}" numFmtId="0"><sharedItems containsSemiMixedTypes="0" containsString="0" containsNumber="1"""")
                    if (vs.size < d.linhas.size) sb.append(""" containsBlank="1"""")
                    if (vs.isNotEmpty() && vs.all { it == Math.floor(it) }) sb.append(""" containsInteger="1"""")
                    if (vs.isNotEmpty()) sb.append(""" minValue="${num(vs.min())}" maxValue="${num(vs.max())}"""")
                    sb.append("/></cacheField>")
                }
                else -> {
                    val ds = d.linhas.mapNotNull { (it[i] as String?)?.let(::isoCompleto) }.sorted()
                    sb.append("""<cacheField name="${esc(nome)}" numFmtId="14"><sharedItems containsNonDate="0"""")
                    if (ds.isNotEmpty()) sb.append(""" containsDate="1"""")
                    sb.append(""" containsString="0"""")
                    if (ds.size < d.linhas.size) sb.append(""" containsBlank="1"""")
                    if (ds.isNotEmpty()) sb.append(""" minDate="${ds.first()}" maxDate="${ds.last()}"""")
                    sb.append("/></cacheField>")
                }
            }
        }
        return sb.append("</cacheFields></pivotCacheDefinition>").toString()
    }

    private fun cacheRecords(c: CacheDin): String {
        val sb = StringBuilder(XML + """<pivotCacheRecords xmlns="$NS_MAIN" xmlns:r="$NS_REL" count="${c.d.linhas.size}">""")
        c.d.linhas.forEach { l ->
            sb.append("<r>")
            l.forEachIndexed { i, v ->
                when (c.d.tipos[i]) {
                    'T' -> sb.append("""<x v="${c.idx(i, v as String?)}"/>""")
                    'N' -> sb.append(if (v == null) "<m/>" else """<n v="${num(v as Double)}"/>""")
                    else -> sb.append(if (v == null) "<m/>" else """<d v="${isoCompleto(v as String)}"/>""")
                }
            }
            sb.append("</r>")
        }
        return sb.append("</pivotCacheRecords>").toString()
    }

    private fun pivotTable(c: CacheDin, id: Int): String {
        val d = c.d
        val nL = c.rotLinhas.size; val nC = c.rotColunas.size
        val r0 = d.linhaInicio
        val sb = StringBuilder(XML + """<pivotTableDefinition xmlns="$NS_MAIN" name="${esc(d.nome)}" cacheId="$id" applyNumberFormats="0" """ +
            """applyBorderFormats="0" applyFontFormats="0" applyPatternFormats="0" applyAlignmentFormats="0" applyWidthHeightFormats="1" """ +
            """dataCaption="Valores" updatedVersion="6" minRefreshableVersion="3" useAutoFormatting="1" itemPrintTitles="1" """ +
            """createdVersion="6" indent="0" outline="1" outlineData="1" multipleFieldFilters="0" """ +
            """rowHeaderCaption="${esc(d.legendaLinha)}" colHeaderCaption="${esc(d.legendaColuna)}">""" +
            """<location ref="A$r0:${col(nC + 1)}${r0 + nL + 2}" firstHeaderRow="1" firstDataRow="2" firstDataCol="1"/>""" +
            """<pivotFields count="${d.cabecalhos.size}">""")
        d.cabecalhos.indices.forEach { i ->
            when (i) {
                d.campoLinha, d.campoColuna -> {
                    val n = c.itens[i]!!.size
                    sb.append("""<pivotField axis="${if (i == d.campoLinha) "axisRow" else "axisCol"}" showAll="0"><items count="${n + 1}">""")
                    repeat(n) { sb.append("""<item x="$it"/>""") }
                    sb.append("""<item t="default"/></items></pivotField>""")
                }
                d.campoValor -> sb.append("""<pivotField dataField="1" showAll="0"/>""")
                else -> sb.append("""<pivotField showAll="0"/>""")
            }
        }
        sb.append("""</pivotFields><rowFields count="1"><field x="${d.campoLinha}"/></rowFields>""")
        sb.append(eixo("rowItems", nL))
        sb.append("""<colFields count="1"><field x="${d.campoColuna}"/></colFields>""")
        sb.append(eixo("colItems", nC))
        sb.append("""<dataFields count="1"><dataField name="${esc(d.nomeValor)}" fld="${d.campoValor}" baseField="0" baseItem="0" numFmtId="4"/></dataFields>""")
        sb.append("""<pivotTableStyleInfo name="PivotStyleMedium2" showRowHeaders="1" showColHeaders="1" showRowStripes="0" showColStripes="0" showLastColumn="1"/></pivotTableDefinition>""")
        return sb.toString()
    }

    private fun eixo(tag: String, n: Int) = buildString {
        append("""<$tag count="${n + 1}">""")
        repeat(n) { append(if (it == 0) "<i><x/></i>" else """<i><x v="$it"/></i>""") }
        append("""<i t="grand"><x/></i></$tag>""")
    }

    private fun escreverResultadoDinamica(a: Aba, c: CacheDin) {
        val d = c.d; val r0 = d.linhaInicio - 1
        val cab = Estilo(negrito = true, fundo = "DCE6F1")
        val moeda = Estilo(formato = "#,##0.00")
        a.texto(r0, 0, d.nomeValor, cab); a.texto(r0, 1, d.legendaColuna, cab)
        a.texto(r0 + 1, 0, d.legendaLinha, cab)
        c.rotColunas.forEachIndexed { j, s -> a.texto(r0 + 1, j + 1, s ?: "(vazio)", cab) }
        a.texto(r0 + 1, c.rotColunas.size + 1, "Total Geral", cab)
        c.rotLinhas.forEachIndexed { i, s ->
            a.texto(r0 + 2 + i, 0, s ?: "(vazio)")
            c.soma[i].forEachIndexed { j, v -> if (v != 0.0) a.num(r0 + 2 + i, j + 1, v, moeda) }
            a.num(r0 + 2 + i, c.rotColunas.size + 1, c.soma[i].sum(), moeda)
        }
        val rt = r0 + 2 + c.rotLinhas.size
        val tot = Estilo(negrito = true, formato = "#,##0.00", fundo = "DCE6F1")
        a.texto(rt, 0, "Total Geral", cab)
        for (j in c.rotColunas.indices) a.num(rt, j + 1, c.soma.sumOf { it[j] }, tot)
        a.num(rt, c.rotColunas.size + 1, c.soma.sumOf { it.sum() }, tot)
    }

    // ====================================================================== utilitários

    companion object {
        const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n"
        const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val NS_XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
        const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
        const val SML = "application/vnd.openxmlformats-officedocument.spreadsheetml"
        const val REL_HEAD = XML + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""
        const val REL_TAIL = "</Relationships>"

        fun rel(id: String, tipo: String, alvo: String) =
            """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$tipo" Target="$alvo"/>"""

        fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }
        fun ref(r: Int, c: Int) = "${col(c)}${r + 1}"
        fun nomeRef(aba: String) = if (aba.all { it.isLetterOrDigit() || it == '_' }) aba else "'${aba.replace("'", "''")}'"
        private fun absoluto(r: String) = r.split(':').joinToString(":") { p -> "$" + p.takeWhile { it.isLetter() } + "$" + p.dropWhile { it.isLetter() } }
        /** Intervalo absoluto para gráficos: 'Análise Resumo'!$N$2:$N$13 */
        fun intervalo(aba: String, r1: Int, c1: Int, r2: Int, c2: Int) = "${nomeRef(aba)}!$${col(c1)}$${r1 + 1}:$${col(c2)}$${r2 + 1}"

        fun serial(dt: LocalDateTime): Double = ChronoUnit.SECONDS.between(LocalDateTime.of(1899, 12, 30, 0, 0), dt) / 86400.0
        fun serialData(iso: String): Double? = isoCompleto(iso)?.let { runCatching { serial(LocalDateTime.parse(it)) }.getOrNull() }
        fun isoCompleto(iso: String): String? = when {
            iso.length >= 19 -> iso.substring(0, 19)
            iso.length == 10 -> iso + "T00:00:00"
            iso.length == 16 -> "$iso:00"
            else -> null
        }
        fun num(d: Double): String = BigDecimal.valueOf(d).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        fun esc(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) when {
                ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
            return sb.toString()
        }
    }
}
