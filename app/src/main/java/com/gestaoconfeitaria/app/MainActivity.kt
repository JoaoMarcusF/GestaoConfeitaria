package com.gestaoconfeitaria.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import com.gestaoconfeitaria.core.Api
import com.gestaoconfeitaria.core.Json
import com.gestaoconfeitaria.core.Obj
import com.gestaoconfeitaria.core.Pagina
import com.gestaoconfeitaria.core.ServidorRede
import com.gestaoconfeitaria.core.ServidorSync
import com.gestaoconfeitaria.core.obj
import com.gestaoconfeitaria.core.str
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

/**
 * O app Android é uma "casca" leve: a interface é a mesma do computador (assets/web),
 * e a lógica (banco, receitas, painel, Excel, sincronização) vem do módulo :core.
 * Aqui ficam só as partes que precisam do celular: câmera/QR, WebView da SEFAZ, arquivos.
 */
class MainActivity : ComponentActivity() {

    private lateinit var api: Api
    private lateinit var web: WebView
    private lateinit var painelSefaz: LinearLayout
    private lateinit var webSefaz: WebView
    private lateinit var tituloSefaz: TextView
    private lateinit var dicaSefaz: TextView
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val extrator by lazy { assets.open("web/extrator.js").bufferedReader().use { it.readText() } }

    // pedidos nativos aguardando resultado (id da chamada JS)
    private var idFoto = 0; private var idSalvar = 0; private var idSefaz = 0
    private var bytesParaSalvar: ByteArray? = null
    private var arquivoCallback: ValueCallback<Array<Uri>>? = null
    private var tentativasSefaz = 0; private var maxTentativasSefaz = 0

    private val escolherFoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) responderErro(idFoto, "cancelado") else lerQrDaFoto(uri, idFoto)
    }
    private val criarArquivo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r: ActivityResult ->
        val uri = r.data?.data; val bytes = bytesParaSalvar; bytesParaSalvar = null
        if (uri == null || bytes == null) { responderErro(idSalvar, "cancelado"); return@registerForActivityResult }
        exec.execute {
            runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
                .onSuccess { responder(idSalvar, true); js("toast('Arquivo salvo.')") }
                .onFailure { responderErro(idSalvar, "Não foi possível salvar: ${it.message}") }
        }
    }
    private val escolherArquivo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r: ActivityResult ->
        arquivoCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        arquivoCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(filesDir, Build.MODEL ?: "Celular", "android")
        api.banco.aoAlterar = { agendarSync(4000) }

        val raiz = FrameLayout(this)
        web = WebView(this)
        raiz.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        montarPainelSefaz(raiz)
        setContentView(raiz)

        // A interface é servida dos assets num endereço https local (sem acesso a arquivos do aparelho)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                // Links externos (ex.: "Ver no site da SEFAZ") abrem no navegador
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                arquivoCallback?.onReceiveValue(null)
                arquivoCallback = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return runCatching { escolherArquivo.launch(i); true }.getOrElse { arquivoCallback = null; false }
            }
        }
        web.addJavascriptInterface(Ponte(), "GCNativo")
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    painelSefaz.visibility == View.VISIBLE -> fecharSefaz("cancelado")
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    // Enquanto o app está aberto, este celular também atende outros celulares na rede (pareamento e sincronização)
    private val rede by lazy { ServidorRede(ServidorSync.PORTA_SYNC) { c, id, corpo -> api.servidor.tratar(c, id, corpo) } }
    private var travaMulticast: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onResume() {
        super.onResume()
        exec.execute {
            runCatching { rede.iniciar() }
            api.servidor.iniciarDescoberta(ServidorSync.PORTA_SYNC)
        }
        travaMulticast = runCatching {
            (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createMulticastLock("gc-descoberta").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        agendarSync(1500)
    }
    override fun onPause() {
        super.onPause()
        main.removeCallbacks(sync)
        api.servidor.pararDescoberta(); rede.parar()
        runCatching { travaMulticast?.release() }; travaMulticast = null
    }
    override fun onDestroy() { web.destroy(); webSefaz.destroy(); exec.shutdown(); super.onDestroy() }

    // ------------------------------------------------------------ ponte JS ↔ Kotlin

    inner class Ponte {
        @JavascriptInterface
        fun chamar(id: Int, metodo: String, argsJson: String) {
            @Suppress("UNCHECKED_CAST")
            val args = runCatching { Json.parse(argsJson) as? Obj }.getOrNull() ?: emptyMap()
            if (metodo.startsWith("nativo.")) { main.post { nativo(id, metodo.removePrefix("nativo."), args) }; return }
            exec.execute {
                try { responder(id, api.chamar(metodo, args)) }
                catch (e: Throwable) { responderErro(id, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun responder(id: Int, valor: Any?) = enviar(id, Json.stringify(obj("ok" to valor)))
    private fun responderErro(id: Int, msg: String) = enviar(id, Json.stringify(obj("erro" to msg)))
    private fun enviar(id: Int, json: String) = main.post { web.evaluateJavascript("gcResposta($id, ${JSONObject.quote(json)})", null) }
    private fun js(codigo: String) = main.post { web.evaluateJavascript(codigo, null) }

    private fun nativo(id: Int, metodo: String, a: Obj) {
        when (metodo) {
            "escanear" -> {
                val op = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
                GmsBarcodeScanning.getClient(this, op).startScan()
                    .addOnSuccessListener { responder(id, it.rawValue) }
                    .addOnCanceledListener { responderErro(id, "cancelado") }
                    .addOnFailureListener { responderErro(id, "Não foi possível abrir o leitor de QR (o Google Play Services pode estar baixando o módulo). ${it.message ?: ""}") }
            }
            "fotoQr" -> { idFoto = id; escolherFoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            "consultarPagina" -> abrirSefaz(id, a.str("url"), a.str("chave"), a["modoChave"] == true)
            "salvarExcel" -> exec.execute {
                @Suppress("UNCHECKED_CAST")
                val bytes = runCatching { api.excel(a["filtro"] as? Obj ?: emptyMap()) }.getOrElse { responderErro(id, it.message ?: "erro"); return@execute }
                main.post { salvarComo(id, "Controle_Financeiro_${java.time.LocalDate.now()}.xlsx", MIME_XLSX, bytes) }
            }
            "salvarArquivo" -> salvarComo(id, a.str("nome"), a.str("mime", "application/octet-stream"), a.str("texto").toByteArray(Charsets.UTF_8))
            else -> responderErro(id, "Função nativa desconhecida: $metodo")
        }
    }

    private fun salvarComo(id: Int, nome: String, mime: String, bytes: ByteArray) {
        idSalvar = id; bytesParaSalvar = bytes
        criarArquivo.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, nome))
    }

    private fun lerQrDaFoto(uri: Uri, id: Int) {
        val img = runCatching { InputImage.fromFilePath(this, uri) }.getOrElse { responderErro(id, "Não consegui abrir a imagem."); return }
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()).process(img)
            .addOnSuccessListener { cods ->
                val qr = cods.mapNotNull { it.rawValue }.firstOrNull { Regex("\\d{44}").containsMatchIn(it.replace(" ", "")) }
                if (qr != null) responder(id, qr) else responderErro(id, "Nenhum QR code de nota encontrado na foto. Tente uma foto mais próxima e nítida.")
            }
            .addOnFailureListener { responderErro(id, "Falha ao ler a foto: ${it.message}") }
    }

    // ------------------------------------------------------------ página da SEFAZ (captcha / páginas com JavaScript)

    @SuppressLint("SetJavaScriptEnabled")
    private fun montarPainelSefaz(raiz: FrameLayout) {
        painelSefaz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); visibility = View.GONE }
        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 16, 16, 8); setBackgroundColor(Color.parseColor("#0F766E")) }
        tituloSefaz = TextView(this).apply { text = "Consulta na SEFAZ"; setTextColor(Color.WHITE); textSize = 16f }
        barra.addView(tituloSefaz, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(Button(this).apply { text = "Ler página"; setOnClickListener { tentativasSefaz = maxTentativasSefaz - 3; lerSefaz(0) } })
        barra.addView(Button(this).apply { text = "Fechar"; setOnClickListener { fecharSefaz("cancelado") } })
        dicaSefaz = TextView(this).apply { setPadding(24, 12, 24, 12); textSize = 13f; setTextColor(Color.parseColor("#55636D")) }
        webSefaz = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.useWideViewPort = true; settings.loadWithOverviewMode = true; settings.builtInZoomControls = true; settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !Pagina.hostPermitido(request.url.host)
                override fun onPageFinished(view: WebView, url: String) { if (idSefaz != 0) lerSefaz(800) }
            }
        }
        painelSefaz.addView(barra); painelSefaz.addView(dicaSefaz)
        painelSefaz.addView(webSefaz, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        raiz.addView(painelSefaz, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun abrirSefaz(id: Int, url: String, chave: String, modoChave: Boolean) {
        if (!Pagina.hostPermitido(Uri.parse(url).host)) { responderErro(id, "Só é permitido abrir sites da Secretaria da Fazenda (.gov.br)."); return }
        if (idSefaz != 0) responderErro(idSefaz, "cancelado")
        idSefaz = id; tentativasSefaz = 0
        maxTentativasSefaz = if (modoChave) 200 else 20          // ~5 min na consulta por chave; ~30 s com QR
        if (modoChave && chave.isNotEmpty()) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Chave de acesso", chave))
            dicaSefaz.text = "A chave de acesso foi copiada. Cole no campo da consulta, resolva a verificação e consulte. O app lê a nota sozinho quando ela aparecer."
        } else dicaSefaz.text = "Se o site pedir uma verificação, resolva e toque em \"Ler página\"."
        painelSefaz.visibility = View.VISIBLE
        webSefaz.loadUrl(url)
        lerSefaz(3000)
    }

    private val tentarSefaz = Runnable {
        if (idSefaz == 0) return@Runnable
        webSefaz.evaluateJavascript("$extrator;GC_EXTRATOR.extrairJanela()") { r ->
            if (idSefaz == 0) return@evaluateJavascript
            val o = runCatching { Json.parse(JSONTokener(r).nextValue() as String) as Obj }.getOrNull()
            val itens = (o?.get("itens") as? List<*>)?.size ?: 0
            if (o != null && itens > 0) {
                val id = idSefaz; idSefaz = 0
                painelSefaz.visibility = View.GONE; webSefaz.loadUrl("about:blank")
                responder(id, o); return@evaluateJavascript
            }
            tentativasSefaz++
            if (tentativasSefaz < maxTentativasSefaz) lerSefaz(1500)
            else dicaSefaz.text = if (o?.get("temCaptcha") == true) "O site pediu verificação. Resolva e toque em \"Ler página\"."
                else "Não encontrei os itens nesta página. Se a nota apareceu, toque em \"Ler página\"; senão, feche e lance manualmente."
        }
    }
    private fun lerSefaz(ms: Long) { main.removeCallbacks(tentarSefaz); main.postDelayed(tentarSefaz, ms) }

    private fun fecharSefaz(motivo: String) {
        main.removeCallbacks(tentarSefaz)
        painelSefaz.visibility = View.GONE
        webSefaz.stopLoading(); webSefaz.loadUrl("about:blank")
        if (idSefaz != 0) { responderErro(idSefaz, motivo); idSefaz = 0 }
    }

    // ------------------------------------------------------------ sincronização automática

    private val sync = Runnable {
        if (api.cliente.alvos().isNotEmpty())
            exec.execute { runCatching { api.cliente.sincronizarTodos() } }   // falhas silenciosas: o outro aparelho pode estar desligado
        agendarSync(60_000)   // enquanto o app está aberto, busca novidades a cada minuto
    }
    private fun agendarSync(ms: Long) { main.removeCallbacks(sync); main.postDelayed(sync, ms) }

    companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
