package com.gestaoconfeitaria.core

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sincronização celular ↔ computador na mesma rede Wi-Fi.
 *
 * Pareamento (uma vez):
 *  1. o celular envia um compromisso H(chave pública + nonce)
 *  2. o computador responde com a chave pública dele; o celular revela a sua
 *  3. ambos calculam um código de 6 dígitos a partir das duas chaves; o computador mostra o código
 *     e o usuário digita no celular. Se bater, não há ninguém no meio (MITM).
 *  4. a chave secreta derivada (ECDH P-256 + HKDF) fica guardada nos dois aparelhos ("lembrar").
 *
 * Depois disso todo tráfego vai cifrado e autenticado com AES-256-GCM.
 */
object Cripto {
    private val rnd = SecureRandom()
    fun aleatorio(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun deB64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun sha256(vararg partes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { partes.forEach { update(it) }; digest() }
    fun hmac(chave: ByteArray, dados: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(chave, "HmacSHA256")); doFinal(dados) }

    fun parDeChaves(): KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    fun segredo(meu: KeyPair, publicaOutro: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicaOutro))
        return KeyAgreement.getInstance("ECDH").run { init(meu.private); doPhase(pub, true); generateSecret() }
    }
    fun derivar(segredo: ByteArray, nonceCel: ByteArray, nonceComp: ByteArray): ByteArray {
        val prk = hmac(sha256(nonceCel, nonceComp), segredo)
        return hmac(prk, "gc-sync-v1".toByteArray() + byteArrayOf(1))
    }
    /** Código de verificação de 6 dígitos mostrado no computador e digitado no celular. */
    fun codigo(pubCel: ByteArray, pubComp: ByteArray, nonceCel: ByteArray, nonceComp: ByteArray): String {
        val h = sha256("gc-sas".toByteArray(), pubCel, pubComp, nonceCel, nonceComp)
        val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return "%06d".format(v % 1_000_000)
    }
    fun cifrar(chave: ByteArray, texto: ByteArray, aad: String): ByteArray {
        val iv = aleatorio(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(chave, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad.toByteArray())
        }
        return iv + c.doFinal(texto)
    }
    fun decifrar(chave: ByteArray, dados: ByteArray, aad: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(chave, "AES"), GCMParameterSpec(128, dados, 0, 12)); updateAAD(aad.toByteArray())
        }
        return c.doFinal(dados, 12, dados.size - 12)
    }
    fun iguais(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/** Identidade deste aparelho e os aparelhos pareados (fica fora do banco: não é sincronizada). */
class Dispositivo(private val arquivo: File, nomePadrao: String) {
    val id: String
    var nome: String; private set
    private val pares = LinkedHashMap<String, MObj>()

    init {
        val o = if (arquivo.exists()) runCatching { Json.parse(arquivo.readText()) as Obj }.getOrNull() else null
        id = o?.str("id")?.ifEmpty { null } ?: ("d" + Banco.novoId())
        nome = o?.str("nome")?.ifEmpty { null } ?: nomePadrao
        o?.lista("pares")?.forEach { @Suppress("UNCHECKED_CAST") pares[it.str("id")] = LinkedHashMap(it) as MObj }
        if (o == null) gravar()
    }

    @Synchronized fun renomear(n: String) { if (n.isNotBlank()) { nome = n.trim().take(60); gravar() } }
    @Synchronized fun par(id: String): MObj? = pares[id]?.let { LinkedHashMap(it) }
    @Synchronized fun pares(): List<MObj> = pares.values.map { LinkedHashMap(it).apply { remove("chave") } }
    @Synchronized fun salvarPar(p: MObj) { pares[p.str("id")] = LinkedHashMap(p); gravar() }
    @Synchronized fun atualizarPar(id: String, campos: Map<String, Any?>) { pares[id]?.putAll(campos); gravar() }
    @Synchronized fun esquecer(id: String) { pares.remove(id); gravar() }
    fun chaveDe(id: String): ByteArray? = par(id)?.str("chave")?.takeIf { it.isNotEmpty() }?.let { Cripto.deB64(it) }

    @Synchronized private fun gravar() {
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(Json.stringify(obj("id" to id, "nome" to nome, "pares" to pares.values.toList())))
    }
}

/**
 * Lado "servidor": atende pareamento e sincronização. Roda no computador o tempo todo e,
 * no celular, enquanto o app está aberto — assim vários celulares sincronizam pelo computador
 * ou diretamente entre si. [tipo] = "computador" ou "celular".
 */
class ServidorSync(private val banco: Banco, private val disp: Dispositivo, private val tipo: String = "computador") {

    class Sessao(val sid: String, val idCel: String, val nomeCel: String, val tipoCel: String, val compromisso: ByteArray, val par: KeyPair,
                 val nonce: ByteArray, val criada: Long = System.currentTimeMillis()) {
        @Volatile var codigo: String? = null
        @Volatile var chave: ByteArray? = null
        @Volatile var concluida = false
    }

    private val sessoes = ConcurrentHashMap<String, Sessao>()
    private val noncesVistos = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Para a interface do computador mostrar o código enquanto o celular aguarda. */
    fun pareamentoPendente(): Obj? {
        limpar()
        return sessoes.values.filter { it.codigo != null && !it.concluida }.maxByOrNull { it.criada }
            ?.let { obj("codigo" to it.codigo, "dispositivo" to it.nomeCel, "sid" to it.sid) }
    }

    fun cancelarPareamento(sid: String) { sessoes.remove(sid) }

    private fun limpar() {
        val agora = System.currentTimeMillis()
        sessoes.values.removeIf { agora - it.criada > 5 * 60_000 || it.concluida && agora - it.criada > 60_000 }
    }

    /** Trata uma requisição. Devolve (status HTTP, corpo). */
    fun tratar(caminho: String, idCliente: String?, corpo: ByteArray): Pair<Int, ByteArray> = try {
        when (caminho) {
            "/gc/info" -> 200 to json(obj("app" to APP, "id" to disp.id, "nome" to disp.nome, "tipo" to tipo))
            "/gc/parear/1" -> parear1(Json.parse(String(corpo, Charsets.UTF_8)) as Obj)
            "/gc/parear/2" -> parear2(Json.parse(String(corpo, Charsets.UTF_8)) as Obj)
            "/gc/parear/3" -> parear3(Json.parse(String(corpo, Charsets.UTF_8)) as Obj)
            "/gc/sync" -> sync(idCliente ?: "", corpo)
            else -> 404 to json(obj("erro" to "caminho desconhecido"))
        }
    } catch (e: Exception) {
        400 to json(obj("erro" to (e.message ?: e.javaClass.simpleName)))
    }

    private fun parear1(o: Obj): Pair<Int, ByteArray> {
        limpar()
        if (sessoes.size > 20) return 429 to json(obj("erro" to "muitas tentativas"))
        val s = Sessao(Banco.novoId(), o.str("id"), o.str("nome").take(60).ifEmpty { "Celular" }, o.str("tipo", "celular"),
            Cripto.deB64(o.str("compromisso")), Cripto.parDeChaves(), Cripto.aleatorio(16))
        sessoes[s.sid] = s
        return 200 to json(obj("sid" to s.sid, "id" to disp.id, "nome" to disp.nome, "tipo" to tipo,
            "pub" to Cripto.b64(s.par.public.encoded), "nonce" to Cripto.b64(s.nonce)))
    }

    private fun parear2(o: Obj): Pair<Int, ByteArray> {
        val s = sessoes[o.str("sid")] ?: return 404 to json(obj("erro" to "sessão expirada"))
        if (s.codigo != null) return 409 to json(obj("erro" to "sessão já usada"))
        val pubCel = Cripto.deB64(o.str("pub")); val nonceCel = Cripto.deB64(o.str("nonce"))
        if (!Cripto.iguais(Cripto.sha256(pubCel, nonceCel), s.compromisso)) {
            sessoes.remove(s.sid); return 403 to json(obj("erro" to "compromisso inválido"))
        }
        s.chave = Cripto.derivar(Cripto.segredo(s.par, pubCel), nonceCel, s.nonce)
        s.codigo = Cripto.codigo(pubCel, s.par.public.encoded, nonceCel, s.nonce)
        return 200 to json(obj("ok" to true))
    }

    private fun parear3(o: Obj): Pair<Int, ByteArray> {
        val s = sessoes[o.str("sid")] ?: return 404 to json(obj("erro" to "sessão expirada"))
        val k = s.chave ?: return 409 to json(obj("erro" to "etapa fora de ordem"))
        val esperado = Cripto.hmac(k, "gc-confirmar|${s.sid}".toByteArray())
        if (!Cripto.iguais(esperado, Cripto.deB64(o.str("prova")))) {
            sessoes.remove(s.sid); return 403 to json(obj("erro" to "código não confere"))
        }
        s.concluida = true
        disp.salvarPar(obj("id" to s.idCel, "nome" to s.nomeCel, "chave" to Cripto.b64(k), "tipo" to s.tipoCel, "conecto" to false,
            "pareadoEm" to System.currentTimeMillis(), "cursorRemoto" to 0L, "cursorEnviado" to 0L))
        return 200 to json(obj("ok" to true, "prova" to Cripto.b64(Cripto.hmac(k, "gc-aceito|${s.sid}".toByteArray()))))
    }

    private fun sync(idCliente: String, corpo: ByteArray): Pair<Int, ByteArray> {
        val k = disp.chaveDe(idCliente) ?: return 401 to json(obj("erro" to "nao_pareado"))
        val pedido = Json.parse(String(Cripto.decifrar(k, corpo, "gc-sync|$idCliente"), Charsets.UTF_8)) as Obj
        if (Math.abs(System.currentTimeMillis() - pedido.long("ts")) > 15 * 60_000) return 400 to json(obj("erro" to "relógio muito diferente"))
        val n = pedido.str("n"); if (!noncesVistos.add(n)) return 400 to json(obj("erro" to "repetido"))
        if (noncesVistos.size > 5000) noncesVistos.clear()
        val recebidos = banco.mesclar(pedido.lista("mudancas"))
        val resposta = obj("ts" to System.currentTimeMillis(), "mudancas" to banco.alteracoesDesde(pedido.long("desde")),
            "seq" to banco.seqAtual(), "nome" to disp.nome, "recebidos" to recebidos)
        val nomeCel = pedido.str("nome").ifEmpty { disp.par(idCliente)?.str("nome") ?: "Celular" }
        disp.atualizarPar(idCliente, mapOf("ultimoSync" to System.currentTimeMillis(), "nome" to nomeCel))
        return 200 to Cripto.cifrar(k, Json.stringify(resposta).toByteArray(Charsets.UTF_8), "gc-sync-resp|$idCliente")
    }

    private fun json(o: Any?) = Json.stringify(o).toByteArray(Charsets.UTF_8)

    @Volatile private var socketDescoberta: DatagramSocket? = null

    /** Para de responder à descoberta (o celular faz isso quando o app vai para o fundo). */
    fun pararDescoberta() { socketDescoberta?.close(); socketDescoberta = null }

    /** Responde aos pedidos de descoberta na rede (UDP broadcast). */
    fun iniciarDescoberta(portaHttp: Int): Thread = Thread {
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORTA_DESCOBERTA))
                socketDescoberta = s
                val buf = ByteArray(512)
                while (!s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (String(p.data, 0, p.length, Charsets.UTF_8).trim() != PEDIDO_DESCOBERTA) continue
                    val r = Json.stringify(obj("app" to APP, "id" to disp.id, "nome" to disp.nome, "porta" to portaHttp, "tipo" to tipo)).toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(r, r.size, p.address, p.port))
                }
            }
        } catch (e: Exception) { if (socketDescoberta != null) System.err.println("Descoberta desativada: ${e.message}") }
    }.apply { isDaemon = true; name = "gc-descoberta"; start() }

    companion object {
        const val APP = "gestao-confeitaria"
        const val PORTA_SYNC = 47822
        const val PORTA_DESCOBERTA = 47823
        const val PEDIDO_DESCOBERTA = "GC-DESCOBRIR-1"
    }
}

/** Lado "cliente": encontra outros aparelhos na rede, pareia e sincroniza com eles. */
class ClienteSync(private val banco: Banco, private val disp: Dispositivo, private val tipo: String = "celular") {

    private class PareamentoEmCurso(val host: String, val porta: Int, val sid: String, val idComp: String, val nomeComp: String,
                                    val tipoComp: String, val chave: ByteArray, val codigo: String)
    @Volatile private var emCurso: PareamentoEmCurso? = null
    @Volatile var ultimoErro: String? = null; private set
    private val travaSync = Any()

    fun descobrir(esperaMs: Int = 1500): List<MObj> {
        val achados = LinkedHashMap<String, MObj>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 300
            val msg = ServidorSync.PEDIDO_DESCOBERTA.toByteArray()
            val destinos = LinkedHashSet<InetAddress>()
            runCatching { destinos += InetAddress.getByName("255.255.255.255") }
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach { destinos += it }
            }
            destinos.forEach { d -> runCatching { s.send(DatagramPacket(msg, msg.size, d, ServidorSync.PORTA_DESCOBERTA)) } }
            val fim = System.currentTimeMillis() + esperaMs
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < fim) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                val o = runCatching { Json.parse(String(p.data, 0, p.length, Charsets.UTF_8)) as Obj }.getOrNull() ?: continue
                if (o.str("app") != ServidorSync.APP || o.str("id") == disp.id) continue
                achados[o.str("id")] = obj("id" to o.str("id"), "nome" to o.str("nome"), "host" to p.address.hostAddress,
                    "porta" to o.long("porta", ServidorSync.PORTA_SYNC.toLong()), "tipo" to o.str("tipo", "computador"),
                    "pareado" to (disp.par(o.str("id")) != null))
            }
        }
        // Atualiza o IP de aparelhos já pareados (o roteador pode ter trocado)
        achados.values.filter { it.bool("pareado") }.forEach { disp.atualizarPar(it.str("id"), mapOf("host" to it.str("host"), "porta" to it.long("porta"))) }
        return achados.values.toList()
    }

    /** Etapas 1 e 2. Devolve o nome do computador; o código aparece na tela dele. */
    fun parearIniciar(host: String, porta: Int): MObj {
        val par = Cripto.parDeChaves(); val nonce = Cripto.aleatorio(16)
        val pub = par.public.encoded
        val r1 = postar(host, porta, "/gc/parear/1", obj("id" to disp.id, "nome" to disp.nome, "tipo" to tipo,
            "compromisso" to Cripto.b64(Cripto.sha256(pub, nonce))))
        val pubComp = Cripto.deB64(r1.str("pub")); val nonceComp = Cripto.deB64(r1.str("nonce"))
        postar(host, porta, "/gc/parear/2", obj("sid" to r1.str("sid"), "pub" to Cripto.b64(pub), "nonce" to Cripto.b64(nonce)))
        val chave = Cripto.derivar(Cripto.segredo(par, pubComp), nonce, nonceComp)
        emCurso = PareamentoEmCurso(host, porta, r1.str("sid"), r1.str("id"), r1.str("nome"), r1.str("tipo", "computador"), chave,
            Cripto.codigo(pub, pubComp, nonce, nonceComp))
        return obj("computador" to r1.str("nome"), "id" to r1.str("id"))
    }

    /** Etapa 3: o usuário digitou o código que aparece no computador. */
    fun parearConfirmar(codigoDigitado: String): MObj {
        val p = emCurso ?: throw IllegalStateException("Comece o pareamento de novo.")
        val digitado = codigoDigitado.filter { it.isDigit() }
        if (digitado != p.codigo) {
            emCurso = null
            throw IllegalArgumentException("O código não confere. Por segurança o pareamento foi cancelado — tente de novo.")
        }
        val r = postar(p.host, p.porta, "/gc/parear/3", obj("sid" to p.sid, "prova" to Cripto.b64(Cripto.hmac(p.chave, "gc-confirmar|${p.sid}".toByteArray()))))
        if (!Cripto.iguais(Cripto.deB64(r.str("prova")), Cripto.hmac(p.chave, "gc-aceito|${p.sid}".toByteArray())))
            throw IllegalStateException("O computador não confirmou o pareamento.")
        disp.salvarPar(obj("id" to p.idComp, "nome" to p.nomeComp, "chave" to Cripto.b64(p.chave), "tipo" to p.tipoComp, "conecto" to true,
            "host" to p.host, "porta" to p.porta, "pareadoEm" to System.currentTimeMillis(), "cursorRemoto" to 0L, "cursorEnviado" to 0L))
        emCurso = null
        return obj("ok" to true, "computador" to p.nomeComp)
    }

    /** Aparelhos que este aparelho procura para sincronizar (os que ele pareou). */
    fun alvos(): List<MObj> = disp.pares().filter { it.bool("conecto") || (it["conecto"] == null && it.str("tipo") == "computador") }

    /** Sincroniza com todos os aparelhos pareados por este. Um aparelho fora do ar não impede os outros. */
    fun sincronizarTodos(): List<MObj> {
        val alvos = alvos()
        val r = alvos.map { a -> runCatching { sincronizar(a.str("id")) }.getOrElse { e -> obj("computador" to a.str("nome"), "erro" to (e.message ?: "falhou"), "enviados" to 0, "recebidos" to 0) } }
        if (alvos.isNotEmpty() && r.all { it["erro"] != null }) throw IllegalStateException(r.first().str("erro"))
        return r
    }

    fun sincronizar(idComp: String): MObj = synchronized(travaSync) {
        val par = disp.par(idComp) ?: throw IllegalArgumentException("Computador não pareado.")
        val k = Cripto.deB64(par.str("chave"))
        var host = par.str("host"); var porta = par.long("porta", ServidorSync.PORTA_SYNC.toLong()).toInt()
        val seqAntes = banco.seqAtual()
        val pedido = obj("ts" to System.currentTimeMillis(), "n" to Banco.novoId(), "nome" to disp.nome,
            "desde" to par.long("cursorRemoto"), "mudancas" to banco.alteracoesDesde(par.long("cursorEnviado")))
        val corpo = Cripto.cifrar(k, Json.stringify(pedido).toByteArray(Charsets.UTF_8), "gc-sync|${disp.id}")
        val bytes = try { enviar(host, porta, "/gc/sync", corpo) } catch (e: Exception) {
            // IP pode ter mudado: procura de novo na rede
            val achado = descobrir().firstOrNull { it.str("id") == idComp }
                ?: throw IllegalStateException("\"${par.str("nome")}\" não foi encontrado na rede. Ele está ligado, no mesmo Wi-Fi e com o app aberto?")
            host = achado.str("host"); porta = achado.long("porta").toInt()
            enviar(host, porta, "/gc/sync", corpo)
        }
        val resp = Json.parse(String(Cripto.decifrar(k, bytes, "gc-sync-resp|${disp.id}"), Charsets.UTF_8)) as Obj
        val aplicados = banco.mesclar(resp.lista("mudancas"))
        disp.atualizarPar(idComp, mapOf("cursorRemoto" to resp.long("seq"), "cursorEnviado" to seqAntes,
            "ultimoSync" to System.currentTimeMillis(), "host" to host, "porta" to porta, "nome" to resp.str("nome").ifEmpty { par.str("nome") }))
        ultimoErro = null
        obj("computador" to resp.str("nome"), "enviados" to pedido.lista("mudancas").size, "recebidos" to aplicados)
    }

    private fun postar(host: String, porta: Int, caminho: String, o: Obj): Obj {
        val b = enviar(host, porta, caminho, Json.stringify(o).toByteArray(Charsets.UTF_8))
        return Json.parse(String(b, Charsets.UTF_8)) as Obj
    }

    private fun enviar(host: String, porta: Int, caminho: String, corpo: ByteArray): ByteArray {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val c = URL("http://$h:$porta$caminho").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("X-GC-Id", disp.id)
        c.outputStream.use { it.write(corpo) }
        val st = c.responseCode
        val r = (if (st < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (st >= 400) {
            val msg = runCatching { (Json.parse(String(r, Charsets.UTF_8)) as Obj).str("erro") }.getOrNull() ?: "erro $st"
            if (msg == "nao_pareado") {
                throw IllegalStateException("O outro aparelho não reconhece mais este. Faça o pareamento de novo.")
            }
            throw IllegalStateException(msg)
        }
        return r
    }
}

/** Baixa a página da SEFAZ (só .gov.br), seguindo redirecionamentos — usado pela versão de computador e como 1ª tentativa no celular. */
object Pagina {
    fun hostPermitido(host: String?): Boolean = host != null && (host.equals("gov.br", true) || host.endsWith(".gov.br", true))

    fun buscar(url: String): MObj {
        var atual = URL(url)
        repeat(6) {
            if (atual.protocol !in listOf("http", "https") || !hostPermitido(atual.host))
                throw IllegalArgumentException("Só é permitido abrir sites da Secretaria da Fazenda (.gov.br).")
            val c = atual.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
            val st = c.responseCode
            if (st in 300..399) {
                atual = URL(atual, c.getHeaderField("Location") ?: throw IllegalStateException("Redirecionamento sem destino"))
                return@repeat
            }
            if (st >= 400) throw IllegalStateException("O site da SEFAZ respondeu com erro $st.")
            val bytes = c.inputStream.use { it.readBytes() }
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1)
                ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(String(bytes, 0, minOf(bytes.size, 4000), Charsets.ISO_8859_1))?.groupValues?.get(1)
                ?: "UTF-8"
            val html = runCatching { String(bytes, charset(cs)) }.getOrElse { String(bytes, Charsets.UTF_8) }
            return obj("url" to atual.toString(), "html" to html)
        }
        throw IllegalStateException("Muitos redirecionamentos.")
    }
}

/**
 * Servidor HTTP mínimo (só java.net), usado para pareamento e sincronização na rede local —
 * funciona igual no Android e no computador.
 */
class ServidorRede(private val porta: Int, private val tratar: (String, String?, ByteArray) -> Pair<Int, ByteArray>) {
    @Volatile private var socket: java.net.ServerSocket? = null
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4) { r -> Thread(r, "gc-rede").apply { isDaemon = true } }

    val ativo get() = socket?.isClosed == false

    fun iniciar() {
        if (ativo) return
        val s = java.net.ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(porta))
        socket = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { atender(c) }
            }
        }, "gc-rede-aceita").apply { isDaemon = true; start() }
    }

    fun parar() { socket?.close(); socket = null }

    private fun atender(c: java.net.Socket) = c.use {
        try {
            c.soTimeout = 30_000
            val ent = java.io.BufferedInputStream(c.getInputStream())
            val cab = ByteArrayOutputStreamLimitado(16 * 1024)
            var ultimos = 0   // últimos 4 bytes lidos, para achar o fim do cabeçalho (\r\n\r\n)
            while (ultimos != 0x0d0a0d0a) {
                val b = ent.read(); if (b < 0) return@use
                cab.write(b)
                ultimos = (ultimos shl 8) or b
            }
            val linhas = cab.toString().split("\r\n")
            val (metodo, caminho) = linhas[0].split(' ').let { (it.getOrElse(0) { "" }) to (it.getOrElse(1) { "/" }.substringBefore('?')) }
            val h = linhas.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val tam = h["content-length"]?.toIntOrNull() ?: 0
            if (tam < 0 || tam > 60 * 1024 * 1024) return@use responder(c, 413, ByteArray(0))
            val corpo = ByteArray(tam)
            var lido = 0
            while (lido < tam) { val n = ent.read(corpo, lido, tam - lido); if (n < 0) break; lido += n }
            val (st, r) = if (metodo == "POST" || caminho == "/gc/info") tratar(caminho, h["x-gc-id"], corpo) else 405 to ByteArray(0)
            responder(c, st, r)
        } catch (e: Exception) { /* conexão interrompida */ }
    }

    private fun responder(c: java.net.Socket, st: Int, corpo: ByteArray) {
        val tipo = if (corpo.isNotEmpty() && corpo[0] == '{'.code.toByte()) "application/json" else "application/octet-stream"
        val cab = "HTTP/1.1 $st ${if (st < 400) "OK" else "Erro"}\r\nContent-Type: $tipo\r\nContent-Length: ${corpo.size}\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply { write(cab.toByteArray(Charsets.US_ASCII)); write(corpo); flush() }
    }

    private class ByteArrayOutputStreamLimitado(private val max: Int) : java.io.ByteArrayOutputStream() {
        override fun write(b: Int) { if (size() >= max) throw IllegalStateException("cabeçalho grande demais"); super.write(b) }
        override fun toString(): String = String(toByteArray(), Charsets.ISO_8859_1)
    }
}
