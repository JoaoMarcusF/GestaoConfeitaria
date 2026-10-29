package com.gestaoconfeitaria.desktop

import com.gestaoconfeitaria.core.Api
import com.gestaoconfeitaria.core.Cripto
import com.gestaoconfeitaria.core.Json
import com.gestaoconfeitaria.core.Obj
import com.gestaoconfeitaria.core.ServidorRede
import com.gestaoconfeitaria.core.ServidorSync
import com.gestaoconfeitaria.core.obj
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * Versão para computador: abre a mesma interface do celular no navegador (só neste computador)
 * e atende o celular na rede Wi-Fi para pareamento e sincronização.
 */
const val PORTA_UI = 47821

fun main(args: Array<String>) {
    val pasta = args.indexOf("--pasta").takeIf { it >= 0 }?.let { File(args[it + 1]) }
        ?: File(System.getProperty("user.home"), "GestaoConfeitaria")
    val semJanela = "--sem-janela" in args || GraphicsEnvironment.isHeadless()
    val endereco = "http://127.0.0.1:$PORTA_UI/"

    val ui = try { HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORTA_UI), 0) } catch (e: BindException) {
        println("O app já está aberto neste computador. Abrindo o navegador…")
        abrirNavegador(endereco); return
    }
    pasta.mkdirs()
    val nomePc = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Computador"
    val api = Api(pasta, "Computador $nomePc", "desktop")
    val token = Cripto.b64(Cripto.aleatorio(24)).replace(Regex("[^A-Za-z0-9]"), "")

    ui.executor = Executors.newFixedThreadPool(6)
    ui.createContext("/") { ex -> tratarUi(ex, api, token) }
    ui.start()

    // Servidor da rede local (somente pareamento e sincronização, tudo autenticado)
    val lanStatus = try {
        ServidorRede(ServidorSync.PORTA_SYNC) { caminho, id, corpo -> api.servidor.tratar(caminho, id, corpo) }.iniciar()
        api.servidor.iniciarDescoberta(ServidorSync.PORTA_SYNC)
        "Aguardando celulares na rede: ${ipsLocais().joinToString()} (porta ${ServidorSync.PORTA_SYNC})"
    } catch (e: Exception) {
        "Sincronização indisponível: porta ${ServidorSync.PORTA_SYNC} ocupada (${e.message})"
    }

    println("Gestão Confeitaria rodando em $endereco")
    println("Dados em: ${pasta.absolutePath}")
    println(lanStatus)
    if (!semJanela) janela(endereco, pasta, lanStatus)
    abrirNavegador(endereco)
}

private fun tratarUi(ex: HttpExchange, api: Api, token: String) {
    try {
        // Proteção contra outros sites abertos no navegador tentando usar o app (DNS rebinding / CSRF)
        val host = ex.requestHeaders.getFirst("Host") ?: ""
        if (host != "127.0.0.1:$PORTA_UI" && host != "localhost:$PORTA_UI") return responder(ex, 403, "text/plain", "Host não permitido".toByteArray())
        val caminho = ex.requestURI.path
        when {
            caminho.startsWith("/api/") -> {
                if (ex.requestMethod != "POST" || ex.requestHeaders.getFirst("X-GC-Token") != token)
                    return responder(ex, 403, "application/json", """{"erro":"não autorizado"}""".toByteArray())
                val corpo = ex.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
                val args = (if (corpo.isBlank()) emptyMap<String, Any?>() else Json.parse(corpo)) as Obj
                val r = try { obj("ok" to api.chamar(caminho.removePrefix("/api/"), args)) }
                        catch (e: Exception) { obj("erro" to (e.message ?: e.javaClass.simpleName)) }
                responder(ex, 200, "application/json; charset=utf-8", Json.stringify(r).toByteArray(Charsets.UTF_8))
            }
            caminho == "/baixar/excel" || caminho == "/baixar/backup" -> {
                val q = consulta(ex.requestURI.rawQuery)
                if (q["t"] != token) return responder(ex, 403, "text/plain", "não autorizado".toByteArray())
                val filtro = q["filtro"]?.let { Json.parse(it) as? Obj } ?: emptyMap()
                val (nome, bytes, tipo) = if (caminho.endsWith("excel"))
                    Triple("Controle_Financeiro_${java.time.LocalDate.now()}.xlsx", api.excel(filtro), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                else {
                    @Suppress("UNCHECKED_CAST") val b = api.chamar("backup", emptyMap()) as Obj
                    Triple(b["nome"].toString(), b["json"].toString().toByteArray(Charsets.UTF_8), "application/json")
                }
                ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"$nome\"")
                responder(ex, 200, tipo, bytes)
            }
            else -> {
                val nome = if (caminho == "/") "index.html" else caminho.removePrefix("/")
                if (nome.contains("..")) return responder(ex, 404, "text/plain", ByteArray(0))
                val recurso = Api::class.java.getResourceAsStream("/web/$nome")
                    ?: return responder(ex, 404, "text/plain", "não encontrado".toByteArray())
                var bytes = recurso.use { it.readBytes() }
                if (nome == "index.html")
                    bytes = String(bytes, Charsets.UTF_8).replace("<!--GC-PLATAFORMA-->",
                        """<script>window.GC_TOKEN="$token";window.GC_PLATAFORMA="desktop";</script>""").toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Cache-Control", "no-store")
                responder(ex, 200, tipoMime(nome), bytes)
            }
        }
    } catch (e: Exception) {
        runCatching { responder(ex, 500, "text/plain", (e.message ?: "erro").toByteArray()) }
    }
}

private fun responder(ex: HttpExchange, status: Int, tipo: String, corpo: ByteArray) {
    ex.responseHeaders.add("Content-Type", tipo)
    ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
    ex.sendResponseHeaders(status, if (corpo.isEmpty()) -1 else corpo.size.toLong())
    if (corpo.isNotEmpty()) ex.responseBody.use { it.write(corpo) } else ex.close()
}

private fun consulta(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.contains('=') }
    .associate { val (k, v) = it.split('=', limit = 2); k to URLDecoder.decode(v, "UTF-8") }

private fun tipoMime(n: String) = when (n.substringAfterLast('.')) {
    "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript; charset=utf-8"; "css" -> "text/css; charset=utf-8"
    "svg" -> "image/svg+xml"; "png" -> "image/png"; "json" -> "application/json"; else -> "application/octet-stream"
}

private fun ipsLocais(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address }.map { it.hostAddress }
}.getOrDefault(emptyList())

private fun abrirNavegador(url: String) {
    val ok = runCatching { if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { Desktop.getDesktop().browse(URI(url)); true } else false }.getOrDefault(false)
    if (!ok) {
        val os = System.getProperty("os.name").lowercase()
        runCatching {
            when {
                "win" in os -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                "mac" in os -> ProcessBuilder("open", url).start()
                else -> ProcessBuilder("xdg-open", url).start()
            }
        }.onFailure { println("Abra no navegador: $url") }
    }
}

/** Janelinha para o usuário saber que o app está aberto e poder fechá-lo. */
private fun janela(url: String, pasta: File, lan: String) = SwingUtilities.invokeLater {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val f = JFrame("Gestão Confeitaria")
    val p = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(16, 20, 16, 20) }
    p.add(JLabel("<html><b>Gestão Confeitaria está aberto.</b><br>A tela do app abre no seu navegador.</html>"))
    p.add(JLabel(" "))
    p.add(JLabel("<html><small>$lan<br>Dados: ${pasta.absolutePath}</small></html>"))
    p.add(JLabel(" "))
    val botoes = JPanel()
    botoes.add(JButton("Abrir no navegador").apply { addActionListener { abrirNavegador(url) } })
    botoes.add(JButton("Fechar o app").apply { addActionListener { exitProcess(0) } })
    p.add(botoes)
    f.contentPane = p
    f.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    f.pack(); f.setLocationRelativeTo(null); f.isVisible = true
}
