package com.gestaoconfeitaria.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * Ponto único de entrada da interface. A mesma interface (HTML/JS) chama estes métodos
 * pela ponte do Android ou por HTTP local no computador.
 */
class Api(pasta: File, nomeDispositivo: String, val plataforma: String) {

    val dispositivo = Dispositivo(File(pasta, "dispositivo.json"), nomeDispositivo)
    val banco = Banco(File(pasta, "banco.json"), dispositivo.id)
    val custos = Custos(banco)
    val painel = Painel(banco, custos)
    val servidor = ServidorSync(banco, dispositivo, if (plataforma == "android") "celular" else "computador")
    val cliente = ClienteSync(banco, dispositivo, if (plataforma == "android") "celular" else "computador")

    private val colecoes = setOf("notas", "insumos", "receitas", "vendas", "config", "equipamentos", "gastosIndiretos", "sitesPreco")

    init {
        banco.criarSeAusente("config", "geral", obj("nomeNegocio" to "", "categorias" to Categorias.padrao,
            "categoriasExcluidas" to listOf(Categorias.PESSOAL), "versaoCategorias" to 2))
        banco.criarSeAusente("config", "tarifas", obj("botijaoKg" to 13.0, "categoriasIndiretas" to Indiretos.categoriasPadrao))
        // Bancos criados antes da categoria de contas: inclui a categoria uma única vez
        banco.get("config", "geral")?.let { c ->
            if (c.long("versaoCategorias") < 2) {
                val cats = c.strs("categorias").toMutableList()
                if (Categorias.CONTAS !in cats) cats.add(cats.indexOf("Entregas").takeIf { it >= 0 } ?: cats.size, Categorias.CONTAS)
                banco.salvar("config", LinkedHashMap(c).apply { this["categorias"] = cats; this["versaoCategorias"] = 2L }, "geral")
            }
        }
    }

    fun chamar(metodo: String, a: Obj): Any? = when (metodo) {
        "inicio" -> obj("plataforma" to plataforma, "versao" to banco.seqAtual(), "config" to banco.get("config", "geral"),
            "dispositivo" to obj("id" to dispositivo.id, "nome" to dispositivo.nome))
        "versao" -> obj("versao" to banco.seqAtual(), "pareamento" to servidor.pareamentoPendente())
        "dados" -> obj(
            "notas" to banco.todos("notas").sortedByDescending { it.str("emissao") },
            "vendas" to banco.todos("vendas").sortedByDescending { it.str("data") },
            "receitas" to custos.receitas(), "insumos" to custos.insumos(),
            "equipamentos" to custos.indiretos.equipamentos(), "gastosIndiretos" to custos.indiretos.modelos(),
            "tarifas" to custos.indiretos.tarifas(),
            "sitesPreco" to banco.todos("sitesPreco"),
            "config" to banco.get("config", "geral"), "versao" to banco.seqAtual())
        "salvar" -> salvar(a.str("col"), a.obj("rec") ?: throw IllegalArgumentException("Faltou o registro."))
        "excluir" -> { checarCol(a.str("col")); banco.excluir(a.str("col"), a.str("id")); true }
        "painel" -> painel.calcular(a)
        "produtos" -> custos.produtos(a.str("busca"))
        "insumoDeProduto" -> custos.criarOuVincular(a.str("chave"), a.str("nome"), a.str("insumoId").ifEmpty { null }, a.obj("conteudo"))
        "insumo" -> banco.get("insumos", a.str("id"))?.let { custos.resumoInsumo(it) }
        "calcularReceita" -> custos.calcularReceita(a.obj("receita") ?: emptyMap())
        "detectarUnidade" -> Unidades.detectar(a.str("descricao"), a.str("unidade"), a.num("quantidade", 1.0))
            ?.let { obj("valor" to it.porUnidade.valor, "base" to it.porUnidade.base, "fonte" to it.fonte) }
        "unidades" -> Unidades.unidadesReceita.keys.toList()
        "calcularIndiretos" -> custos.indiretos.calcular(a.lista("itens"))
        "custoEquipamento" -> custos.indiretos.custoHora(a.obj("equipamento") ?: emptyMap(), a.obj("tarifas") ?: custos.indiretos.tarifas())
            .let { (v, aviso) -> obj("custoHora" to v, "aviso" to aviso) }
        "chaveInfo" -> {
            val ch = ChaveAcesso.extrair(a.str("chave")) ?: throw IllegalArgumentException("A chave de acesso tem 44 dígitos.")
            ChaveAcesso.info(ch).also { it["existe"] = banco.get("notas", ch) != null }
        }
        "buscarPagina" -> Pagina.buscar(a.str("url"))
        // Pesquisar preço: só baixa páginas dos sites que o usuário cadastrou
        "buscarSite" -> Pagina.baixar(a.str("url"), ::siteCadastrado,
            "Este endereço não é de um site cadastrado em Pesquisar preço.", "O site")
        "medidas" -> a.lista("itens").map { i ->
            val c = Unidades.detectar(i.str("nome"), "UN", 1.0)?.porUnidade
            val preco = i.numOuNull("preco")
            if (c == null || c.valor <= 0 || preco == null) null
            else obj("valor" to c.valor, "base" to c.base, "unidadeExib" to Unidades.nomeBase(c.base),
                "precoExib" to preco / c.valor * Unidades.fatorExibicao(c.base))
        }
        "importarSefaz" -> importar(Importadores.deSefaz(a.obj("dados") ?: emptyMap(), a.str("url"), a.str("chave").ifEmpty { null }))
        "importarXml" -> importar(Importadores.deXml(a.str("xml")))
        "importarPlanilha" -> {
            val notas = Importadores.dePlanilha(Cripto.deB64(a.str("base64")))
            val novas = notas.count { banco.get("notas", it.str("id")) == null }
            banco.emLote { notas.forEach { banco.salvar("notas", it) } }
            obj("notas" to notas.size, "novas" to novas, "itens" to notas.sumOf { it.lista("itens").size })
        }
        "exportarExcel" -> obj("nome" to "Controle_Financeiro_${LocalDate.now()}.xlsx", "base64" to Cripto.b64(excel(a)))
        "backup" -> obj("nome" to "backup_gestao_${LocalDate.now()}.json",
            "json" to Json.stringify(obj("app" to ServidorSync.APP, "registros" to banco.alteracoesDesde(0))))
        "restaurarBackup" -> {
            val o = Json.parse(a.str("json")) as? Obj ?: throw IllegalArgumentException("Arquivo de backup inválido.")
            obj("aplicados" to banco.mesclar(o.lista("registros")))
        }
        // ---- dispositivos
        "dispositivos" -> obj("id" to dispositivo.id, "nome" to dispositivo.nome, "pares" to dispositivo.pares())
        "renomearDispositivo" -> { dispositivo.renomear(a.str("nome")); true }
        "esquecerDispositivo" -> { dispositivo.esquecer(a.str("id")); true }
        "pareamentoPendente" -> servidor.pareamentoPendente()
        "cancelarPareamento" -> { servidor.cancelarPareamento(a.str("sid")); true }
        "descobrir" -> cliente.descobrir()
        "parearIniciar" -> cliente.parearIniciar(a.str("host"), a.long("porta", ServidorSync.PORTA_SYNC.toLong()).toInt())
        "parearConfirmar" -> cliente.parearConfirmar(a.str("codigo"))
        "sincronizar" -> if (a.str("id").isNotEmpty()) listOf(cliente.sincronizar(a.str("id"))) else cliente.sincronizarTodos()
        else -> throw IllegalArgumentException("Método desconhecido: $metodo")
    }

    /** O host pertence a um site cadastrado em Pesquisar preço (mesmo domínio, qualquer subdomínio)? */
    fun siteCadastrado(host: String?): Boolean {
        if (!Pagina.hostPublico(host)) return false
        val base = Pagina.dominioBase(host!!)
        return banco.todos("sitesPreco").any { s ->
            runCatching { Pagina.dominioBase(java.net.URL(s.str("url")).host) == base }.getOrDefault(false)
        }
    }

    fun excel(filtro: Obj): ByteArray = ByteArrayOutputStream().also { Relatorio(banco, custos, painel).gerar(filtro, it) }.toByteArray()

    private fun checarCol(c: String) { if (c !in colecoes) throw IllegalArgumentException("Coleção inválida: $c") }

    private fun salvar(col: String, rec: Obj): Obj {
        checarCol(col)
        @Suppress("UNCHECKED_CAST")
        val r = copiaProfunda(rec) as MObj
        when (col) {
            "notas" -> {
                if (r.str("origem").isEmpty()) r["origem"] = "manual"
                val itens = r.lista("itens").map { LinkedHashMap(it) as MObj }
                itens.forEach { i ->
                    if (i.str("categoria").isEmpty()) i["categoria"] = Categorias.sugerir(i.str("descricao"))
                    val q = i.num("quantidade", 1.0)
                    if (i["valorTotal"] == null) i["valorTotal"] = q * i.num("valorUnitario")
                    if (i["valorUnitario"] == null && q > 0) i["valorUnitario"] = i.num("valorTotal") / q
                }
                r["itens"] = itens
                if (r["valorTotal"] == null) r["valorTotal"] = itens.sumOf { it.numOuNull("valorAjustado") ?: it.num("valorTotal") }
            }
            "vendas" -> if (r["valorTotal"] == null) r["valorTotal"] = r.num("quantidade", 1.0) * r.num("valorUnitario")
            "config" -> if (r.str("id") != "tarifas") r["id"] = "geral"
            "equipamentos" -> if (r.str("categoria").isEmpty()) r["categoria"] = Indiretos.categoriaDaFonte(r.str("fonte"))
        }
        return banco.salvar(col, r)
    }

    private fun importar(nota: MObj): Obj {
        val existia = banco.get("notas", nota.str("id"))
        if (existia != null) {
            // Preserva o que o usuário já ajustou (categorias, conferido, observações)
            nota["conferido"] = existia.bool("conferido")
            nota["observacoes"] = existia.str("observacoes")
            val antigos = existia.lista("itens").associateBy { it.str("descricao") + "|" + it.str("valorTotal") }
            nota.lista("itens").forEach { i ->
                antigos[i.str("descricao") + "|" + i.str("valorTotal")]?.let { a ->
                    (i as MObj)["categoria"] = a.str("categoria").ifEmpty { i.str("categoria") }
                    i["observacao"] = a.str("observacao")
                }
            }
        }
        if (nota.lista("itens").isEmpty()) throw IllegalArgumentException("Nenhum item encontrado na nota.")
        return obj("nota" to banco.salvar("notas", nota), "existia" to (existia != null))
    }
}
