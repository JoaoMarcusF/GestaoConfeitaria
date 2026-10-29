package com.gestaoconfeitaria.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Banco de dados do app: um arquivo JSON com coleções de documentos.
 *
 * Cada registro guarda, além dos dados:
 *  - atualizadoEm: relógio lógico híbrido (ms) usado para decidir qual versão vence numa sincronização
 *  - origem: aparelho que fez a última alteração (desempate)
 *  - seq: número sequencial local, para saber o que mudou desde a última sincronização
 *  - excluido: exclusões também são sincronizadas
 *
 * Coleções usadas: notas, insumos, receitas, vendas, config.
 */
class Banco(private val arquivo: File, val dispositivoId: String) {

    class Registro(
        val col: String, val id: String, val dados: Obj,
        val atualizadoEm: Long, val excluido: Boolean, val origem: String, val seq: Long,
    )

    private val cols = HashMap<String, LinkedHashMap<String, Registro>>()
    private var seq = 0L
    private var relogio = 0L
    private var lote = 0
    private var sujo = false

    /** Chamado (fora do lock) depois de cada alteração local — usado para disparar a sincronização. */
    @Volatile var aoAlterar: (() -> Unit)? = null

    init { carregar() }

    // ------------------------------------------------------------ leitura

    @Synchronized fun todos(col: String): List<Obj> =
        cols[col]?.values?.filter { !it.excluido }?.map { comId(it) } ?: emptyList()

    @Synchronized fun get(col: String, id: String): Obj? = cols[col]?.get(id)?.takeIf { !it.excluido }?.let { comId(it) }

    @Synchronized fun seqAtual() = seq

    private fun comId(r: Registro): Obj {
        @Suppress("UNCHECKED_CAST")
        val m = copiaProfunda(r.dados) as MObj
        m["id"] = r.id
        m["atualizadoEm"] = r.atualizadoEm
        return m
    }

    // ------------------------------------------------------------ escrita

    fun salvar(col: String, dados: Obj, id: String? = null): Obj {
        val r = synchronized(this) {
            val rid = id ?: dados.str("id").ifEmpty { novoId() }
            @Suppress("UNCHECKED_CAST")
            val limpo = (copiaProfunda(dados) as MObj).apply { remove("id"); remove("atualizadoEm") }
            val reg = Registro(col, rid, limpo, tique(), false, dispositivoId, ++seq)
            cols.getOrPut(col) { LinkedHashMap() }[rid] = reg
            gravar()
            comId(reg)
        }
        aoAlterar?.invoke()
        return r
    }

    /**
     * Cria um registro padrão (ex.: configurações) com data "antiga", para que numa sincronização
     * qualquer versão editada pelo usuário em outro aparelho prevaleça sobre o padrão.
     */
    @Synchronized fun criarSeAusente(col: String, id: String, dados: Obj) {
        if (cols[col]?.get(id) != null) return
        cols.getOrPut(col) { LinkedHashMap() }[id] = Registro(col, id, dados, 1L, false, "", ++seq)
        gravar()
    }

    fun excluir(col: String, id: String) {
        synchronized(this) {
            val atual = cols[col]?.get(id) ?: return
            if (atual.excluido) return
            cols[col]!![id] = Registro(col, id, emptyMap(), tique(), true, dispositivoId, ++seq)
            gravar()
        }
        aoAlterar?.invoke()
    }

    /** Agrupa várias alterações numa única gravação do arquivo (ex.: importar uma planilha). */
    fun <T> emLote(bloco: () -> T): T {
        synchronized(this) { lote++ }
        try { return bloco() } finally {
            synchronized(this) { lote--; if (lote == 0 && sujo) gravar() }
        }
    }

    // ------------------------------------------------------------ sincronização

    @Synchronized fun alteracoesDesde(seqRemoto: Long): List<Obj> =
        cols.values.flatMap { it.values }.filter { it.seq > seqRemoto }.sortedBy { it.seq }.map { serializar(it) }

    /** Aplica registros vindos de outro aparelho. Vence a versão com maior atualizadoEm (e origem, no empate). */
    fun mesclar(regs: List<Obj>): Int {
        var aplicados = 0
        synchronized(this) {
            for (o in regs) {
                val col = o.str("c"); val id = o.str("i")
                if (col.isEmpty() || id.isEmpty()) continue
                val t = o.long("t"); val origem = o.str("o")
                relogio = maxOf(relogio, t)
                val atual = cols[col]?.get(id)
                val vence = atual == null || t > atual.atualizadoEm || (t == atual.atualizadoEm && origem > atual.origem)
                if (!vence) continue
                cols.getOrPut(col) { LinkedHashMap() }[id] =
                    Registro(col, id, o.obj("d") ?: emptyMap(), t, o.bool("x"), origem, ++seq)
                aplicados++
            }
            if (aplicados > 0) gravar()
        }
        return aplicados
    }

    // ------------------------------------------------------------ arquivo

    private fun tique(): Long { relogio = maxOf(System.currentTimeMillis(), relogio + 1); return relogio }

    private fun serializar(r: Registro) = obj(
        "c" to r.col, "i" to r.id, "d" to r.dados, "t" to r.atualizadoEm, "x" to r.excluido, "o" to r.origem, "s" to r.seq,
    )

    private fun carregar() {
        if (!arquivo.exists()) return
        val raiz = Json.parse(arquivo.readText(Charsets.UTF_8)) as? Obj ?: return
        seq = raiz.long("seq"); relogio = raiz.long("relogio")
        for (o in raiz.lista("registros")) {
            val r = Registro(o.str("c"), o.str("i"), o.obj("d") ?: emptyMap(), o.long("t"), o.bool("x"), o.str("o"), o.long("s"))
            cols.getOrPut(r.col) { LinkedHashMap() }[r.id] = r
        }
    }

    private fun gravar() {
        if (lote > 0) { sujo = true; return }
        sujo = false
        val raiz = obj("formato" to 1, "seq" to seq, "relogio" to relogio,
            "registros" to cols.values.flatMap { it.values }.map { serializar(it) })
        arquivo.parentFile?.mkdirs()
        val tmp = File(arquivo.path + ".tmp")
        tmp.writeText(Json.stringify(raiz), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), arquivo.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), arquivo.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val rnd = SecureRandom()
        fun novoId(): String = ByteArray(9).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
