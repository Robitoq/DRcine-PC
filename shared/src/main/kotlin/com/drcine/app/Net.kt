package com.drcine.app

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Camada de rede única do app.
 *  - Interceptor que injeta User-Agent de navegador + Referer (evita 403/502 de servidores que bloqueiam apps).
 *  - Cliente da API (timeout total curto), cliente de sondagem (3 s) e cliente de mídia (sem timeout total).
 *  - O player de cada plataforma usa o MESMO cliente de mídia, então também leva os headers.
 */
object Net {
    // Atualize o número da versão de tempos em tempos para continuar parecendo um navegador atual.
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    /** Endereço "de mentira" que o próprio app atende (nunca sai para a internet): é por ele que o player lê o manifesto DASH do YouTube. */
    const val LOCAL_HOST = "drcine.local"
    private val localBodies = ConcurrentHashMap<String, ByteArray>()

    /** Guarda um texto para o player pedir em https://drcine.local[path] (path começa com "/", ex.: "/yt/abc.mpd"). */
    fun localPut(path: String, body: String) {
        if (localBodies.size > 40) localBodies.clear()   // são só alguns KB cada; limpa de vez em quando para não crescer
        localBodies[path] = body.toByteArray(Charsets.UTF_8)
    }

    private val localInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.url.host != LOCAL_HOST) return@Interceptor chain.proceed(req)
        val bytes = localBodies[req.url.encodedPath]
        val ok = bytes != null
        Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1)
            .code(if (ok) 200 else 404).message(if (ok) "OK" else "Not Found")
            .header("Content-Type", "application/dash+xml")
            .body((bytes ?: ByteArray(0)).toResponseBody("application/dash+xml".toMediaType()))
            .build()
    }

    /** Só preenche o que ainda não foi definido; headers específicos do stream têm prioridade. */
    private val headersInterceptor = Interceptor { chain ->
        val r = chain.request()
        val b = r.newBuilder()
        if (r.header("User-Agent") == null) b.header("User-Agent", UA)
        if (r.header("Accept") == null) b.header("Accept", "*/*")
        if (r.header("Accept-Language") == null) b.header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        if (r.header("Referer") == null) b.header("Referer", "${r.url.scheme}://${r.url.host}/")
        chain.proceed(b.build())
    }

    /**
     * UMA instância só (singleton): todos os clientes abaixo nascem dela com newBuilder(), que REAPROVEITA o mesmo
     * pool de conexões e o mesmo dispatcher. Assim o app não refaz TCP/TLS a cada link, e o limite padrão de
     * 5 chamadas simultâneas por servidor (que atrasava vários addons no mesmo domínio) sobe para 32.
     */
    private val dispatcher = Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 32 }
    private val pool = ConnectionPool(32, 5, TimeUnit.MINUTES)

    private val base: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(pool)
        .addInterceptor(localInterceptor)
        .addInterceptor(headersInterceptor)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Manifest, catálogo, meta e lista de streams dos addons. */
    val api: OkHttpClient = base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    /**
     * Busca de servidores (lista de streams dos addons): limite ESTRITO. Addon que não responde em ~4 s é cortado de verdade
     * (a conexão é cancelada, não só ignorada) e não segura os outros. Usa o mesmo pool de conexões do resto do app.
     */
    val search: OkHttpClient = base.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(4500, TimeUnit.MILLISECONDS).build()

    /** Manifest de addon: servidores novos/lentos (cold start) precisam de mais tempo que o catálogo. */
    val slow: OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    /** Cliente "cru", SEM o interceptor (não inventa Referer/UA): plano B para servidores que bloqueiam o perfil de navegador. */
    val plain: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    /** Igual ao [plain], mas com os limites curtos da busca de servidores (~4 s). */
    val plainFast: OkHttpClient = plain.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(4500, TimeUnit.MILLISECONDS).build()

    /** Reprodução: NÃO pode ter callTimeout (um filme é uma chamada de horas). */
    val media: OkHttpClient = base.newBuilder().readTimeout(25, TimeUnit.SECONDS).build()

    /** Cliente do validador de links: tudo limitado a [ms] milissegundos. */
    fun probeClient(ms: Long): OkHttpClient = base.newBuilder()
        .callTimeout(ms, TimeUnit.MILLISECONDS)
        .connectTimeout(ms, TimeUnit.MILLISECONDS)
        .readTimeout(ms, TimeUnit.MILLISECONDS)
        .build()

    // Headers que o próprio addon pede (behaviorHints.proxyHeaders.request), por host do stream.
    private val hostHeaders = ConcurrentHashMap<String, Map<String, String>>()

    fun register(s: Stream) {
        if (s.headers.isEmpty()) return
        val host = runCatching { java.net.URI(s.url ?: return).host }.getOrNull() ?: return
        hostHeaders[host] = s.headers
    }

    /** Headers para um DOMÍNIO inteiro (o host e todos os subdomínios dele). Ex.: "googlevideo.com" vale para rr3---sn-xyz.googlevideo.com. */
    fun registerHost(domain: String, h: Map<String, String>) { hostHeaders["*.$domain"] = h }

    // YouTube: o link do googlevideo diz quem o pediu (c=IOS na query; nos pedaços do HLS vem como /c/IOS/ no caminho) e o servidor
    // espera o User-Agent desse mesmo cliente. Com isso, links de clientes diferentes (iPhone, óculos VR…) convivem na mesma lista de servidores.
    private val clientHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val clientQuery = Regex("""[?&]c=([A-Za-z_]+)""")
    private val clientPath = Regex("""/c/([A-Za-z_]+)/""")

    fun registerClient(client: String, h: Map<String, String>) { clientHeaders[client] = h }

    /** Igual a [headersFor], mas em link do googlevideo usa o cabeçalho do cliente do YouTube que pediu aquele link (se for conhecido). */
    fun headersFor(host: String?, url: String?): Map<String, String>? {
        if (url != null && host != null && host.endsWith("googlevideo.com") && clientHeaders.isNotEmpty()) {
            val c = (clientQuery.find(url) ?: clientPath.find(url))?.groupValues?.get(1)
            if (c != null) {
                val h = clientHeaders[c]
                if (h != null) return h
            }
        }
        return headersFor(host)
    }

    /** Headers extras que o addon pediu para este host (o player de cada plataforma aplica). */
    fun headersFor(host: String?): Map<String, String>? {
        var h = host ?: return null
        hostHeaders[h]?.let { return it }
        while (true) {
            val i = h.indexOf('.')
            if (i < 0) return null
            h = h.substring(i + 1)
            hostHeaders["*.$h"]?.let { return it }
        }
    }

    // ---- Memória de servidores ruins: host que falhou 2+ vezes fica "de castigo" por 3 min (só entra se não sobrar outro) ----
    private class HostStat { @Volatile var fails = 0; @Volatile var until = 0L }
    private val hostStats = ConcurrentHashMap<String, HostStat>()
    private fun hostOf(url: String?): String? = runCatching { java.net.URI(url ?: return null).host }.getOrNull()

    fun markBad(url: String?) {
        val h = hostOf(url) ?: return
        val st = hostStats.getOrPut(h) { HostStat() }
        st.fails++
        st.until = System.currentTimeMillis() + 180_000L
    }

    fun markGood(url: String?) { hostOf(url)?.let { hostStats.remove(it) } }

    fun isBad(url: String?): Boolean {
        val h = hostOf(url) ?: return false
        val st = hostStats[h] ?: return false
        if (System.currentTimeMillis() > st.until) { hostStats.remove(h); return false }
        return st.fails >= 2
    }
}
