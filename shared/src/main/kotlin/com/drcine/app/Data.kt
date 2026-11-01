package com.drcine.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Item(val id: String, val type: String, val title: String, val poster: String?, val backdrop: String?, val overview: String, val rating: String, val year: String)
/**
 * Um catálogo do addon. [genres] = gêneros que o addon oferece nesse catálogo (Ação, Comédia…); cada gênero vira
 * uma categoria/prateleira própria ([genre] preenchido). [genreReq] = o addon só responde se mandarem um gênero.
 */
data class Cat(val type: String, val id: String, val name: String, val browse: Boolean, val search: Boolean,
               val genres: List<String> = emptyList(), val genre: String? = null, val genreReq: Boolean = false)
/**
 * Regra de um recurso do manifest (stream ou meta): para quais [types] e quais prefixos de id ([prefixes], ex.: "tt" = IMDb)
 * o addon responde. Lista vazia = sem restrição. É o que o Stremio usa para não pedir ao addon o que ele não sabe responder.
 */
data class AddonRule(val types: List<String> = emptyList(), val prefixes: List<String> = emptyList()) {
    fun ok(type: String, id: String) = (types.isEmpty() || type in types) && (prefixes.isEmpty() || prefixes.any { id.startsWith(it) })
}

/** O que o manifest diz sobre o addon (aparece nos cartões de Ajustes > Addons). Vazio = addon instalado antes de isto ser guardado. */
data class AddonInfo(
    val logo: String = "", val version: String = "", val description: String = "", val types: List<String> = emptyList(),
    val configurable: Boolean = false, val stream: AddonRule = AddonRule(), val meta: AddonRule = AddonRule(),
    val subs: AddonRule = AddonRule(), val rev: Int = 0,
) {
    /** Dados guardados por uma versão antiga do app (sem logo/versão ou sem a regra de legendas): vale reler o manifest. */
    val stale get() = rev < REV
    companion object { const val REV = 2 }
}

data class Addon(val name: String, val url: String, val cats: List<Cat>, val stream: Boolean, val meta: Boolean, val info: AddonInfo = AddonInfo(), val subs: Boolean = false)

fun jsonStrings(a: JSONArray?): List<String> =
    if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() && it != "null" }

private fun ruleToJson(r: AddonRule) = JSONObject().put("types", JSONArray(r.types)).put("prefixes", JSONArray(r.prefixes))
private fun ruleFromJson(o: JSONObject?) = if (o == null) AddonRule() else AddonRule(jsonStrings(o.optJSONArray("types")), jsonStrings(o.optJSONArray("prefixes")))

private fun infoToJson(i: AddonInfo) = JSONObject().put("logo", i.logo).put("version", i.version).put("description", i.description)
    .put("types", JSONArray(i.types)).put("configurable", i.configurable).put("stream", ruleToJson(i.stream)).put("meta", ruleToJson(i.meta))
    .put("subs", ruleToJson(i.subs)).put("rev", i.rev)

private fun infoFromJson(o: JSONObject?) = if (o == null) AddonInfo() else AddonInfo(
    o.optString("logo"), o.optString("version"), o.optString("description"), jsonStrings(o.optJSONArray("types")),
    o.optBoolean("configurable"), ruleFromJson(o.optJSONObject("stream")), ruleFromJson(o.optJSONObject("meta")),
    ruleFromJson(o.optJSONObject("subs")), o.optInt("rev", 0))
data class Stream(val label: String, val url: String?, val external: String?, val headers: Map<String, String> = emptyMap())
data class Ep(val id: String, val season: Int, val number: Int, val title: String)
/** Uma página de catálogo: itens já filtrados + quantidade BRUTA devolvida pelo addon (base do skip). */
data class Page(val items: List<Item>, val raw: Int)
/** Uma prateleira (linha) da tela: carrega a referência addon+categoria para poder paginar. */
data class ShelfRow(val label: String, val items: List<Item>, val raw: Int, val ref: Ref)

/** Um integrante do elenco: nome e foto (endereço; vazio = sem foto). */
data class CastMember(val name: String, val photo: String)

/** Servidores de um título, chegando ao vivo: [items] cresce conforme os links passam no teste (quem chega primeiro fica na frente). */
class StreamSession {
    val items = CopyOnWriteArrayList<Pair<String, Stream>>()
    @Volatile var finishedAt = 0L
    val startedAt = System.currentTimeMillis()

    /** Fluxo ao vivo: cada servidor que passa no teste é emitido NA HORA (quem observa não precisa ficar perguntando). */
    private val _live = MutableStateFlow<List<Pair<String, Stream>>>(emptyList())
    val live: StateFlow<List<Pair<String, Stream>>> = _live
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished

    var done: Boolean
        get() = _finished.value
        set(v) { _finished.value = v }

    @Synchronized fun add(p: Pair<String, Stream>) {
        if (items.none { it.second.url == p.second.url }) { items.add(p); _live.value = items.toList() }
    }

    /** Dorme até chegar servidor novo (além dos [seen] já vistos) ou a busca terminar; no máximo [ms]. Substitui o "olhar a cada 25 ms". */
    suspend fun awaitChange(seen: Int, ms: Long) {
        withTimeoutOrNull(ms) { kotlinx.coroutines.flow.combine(_live, _finished) { l, d -> l.size > seen || d }.first { it } }
    }
}

private val PT_RX = Regex("""\b(dublad[oa]s?|dual|pt-?br|pt_br|portugu[eê]s|brasil(eiro)?|brazil(ian)?|nacional)\b|\bbr\b|🇧🇷""", RegexOption.IGNORE_CASE)
private val PTPT_RX = Regex("""\bpt-?pt\b""", RegexOption.IGNORE_CASE)

/** O texto do servidor indica áudio em português do Brasil (dublado, dual áudio, PT-BR…)? Legendado não conta: o áudio é o original. */
fun isPtBr(label: String): Boolean = !PTPT_RX.containsMatchIn(label) && PT_RX.containsMatchIn(label)

/** Ref: "de onde vem" uma prateleira (addon + categoria), usado para paginar. */
data class Ref(val a: Addon, val c: Cat)

/**
 * Armazenamento chave/valor da plataforma (Android: SharedPreferences; PC: arquivo JSON).
 * O Store (histórico, addons, favoritos, posição, servidor) só conhece esta interface.
 */
interface Prefs {
    fun getString(k: String): String?
    fun putString(k: String, v: String)
    fun getLong(k: String, d: Long): Long
    fun putLong(k: String, v: Long)
    fun remove(k: String)
    fun keys(): Set<String>
}

/** Mesmo comportamento do Uri.encode do Android: mantém letras, números e _-!.~'()* (+ [allow]); o resto vira %XX em UTF-8. */
fun uriEncode(s: String, allow: String = ""): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val v = b.toInt() and 0xFF
        val c = v.toChar()
        if (v < 0x80 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_-!.~'()*" || c in allow)) sb.append(c)
        else sb.append('%').append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 15])
    }
    return sb.toString()
}

fun JSONObject.s(k: String): String? = optString(k).takeIf { it.isNotEmpty() && it != "null" }

object Store {
    private lateinit var p: Prefs
    fun init(prefs: Prefs) { p = prefs }

    fun addons(): List<Addon> = runCatching {
        val a = JSONArray((p.getString("addons2") ?: "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            val cs = o.getJSONArray("cats")
            Addon(o.getString("name"), o.getString("url"),
                List(cs.length()) { j -> val c = cs.getJSONObject(j); Cat(c.getString("type"), c.getString("id"), c.getString("name"), c.getBoolean("browse"), c.getBoolean("search"),
                    c.optJSONArray("genres")?.let { g -> List(g.length()) { k -> g.getString(k) } } ?: emptyList(), null, c.optBoolean("genreReq", false)) },
                o.getBoolean("stream"), o.getBoolean("meta"), infoFromJson(o.optJSONObject("info")), o.optBoolean("subs"))
        }
    }.getOrDefault(emptyList())

    fun manifestAt(): Long = p.getLong("manifest_at", 0L)
    fun setManifestAt(v: Long) = p.putLong("manifest_at", v)

    /**
     * Avisa o app quando a LISTA de addons muda (instalou ou removeu, por qualquer tela). O app recarrega Início, Filmes,
     * Séries e Catálogo sozinho. Reler os manifests (refreshAddons) não muda a lista, então não dispara isto.
     */
    @Volatile var onAddonsChanged: (() -> Unit)? = null

    fun saveAddons(l: List<Addon>) {
        val before = addons().map { it.url }.toSet()
        val a = JSONArray()
        l.forEach { ad ->
            val cs = JSONArray()
            ad.cats.forEach { cs.put(JSONObject().put("type", it.type).put("id", it.id).put("name", it.name).put("browse", it.browse).put("search", it.search).put("genres", JSONArray(it.genres)).put("genreReq", it.genreReq)) }
            a.put(JSONObject().put("name", ad.name).put("url", ad.url).put("stream", ad.stream).put("meta", ad.meta).put("subs", ad.subs).put("info", infoToJson(ad.info)).put("cats", cs))
        }
        p.putString("addons2", a.toString())
        if (l.map { it.url }.toSet() != before) onAddonsChanged?.invoke()
    }

    fun list(n: String): List<Item> = runCatching {
        val a = JSONArray((p.getString(n) ?: "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            Item(o.getString("id"), o.getString("type"), o.getString("title"), o.s("poster"), o.s("backdrop"), "", "", "")
        }
    }.getOrDefault(emptyList())

    private fun save(n: String, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("title", it.title).put("poster", it.poster ?: "").put("backdrop", it.backdrop ?: "")) }
        p.putString(n, a.toString())
    }
    fun covers(): List<String> = runCatching {
        val a = JSONArray((p.getString("covers") ?: "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
    fun saveCovers(l: List<String>) { p.putString("covers", JSONArray(l).toString()) }
    // ---- Buscas recentes ----
    fun recents(): List<String> = runCatching {
        val a = JSONArray((p.getString("recents") ?: "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
    private fun saveRecents(l: List<String>) { p.putString("recents", JSONArray(l).toString()) }
    fun addRecent(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        // some "Scar" se agora salvou "Scarface" (pedaços digitados não poluem a lista)
        saveRecents((listOf(t) + recents().filter { !t.startsWith(it, ignoreCase = true) }).take(12))
    }
    fun removeRecent(q: String) = saveRecents(recents().filter { it != q })
    fun clearRecents() = saveRecents(emptyList())

    /** Tira do "Continuar assistindo" e apaga posição salva / último episódio daquele título. */
    fun forget(i: Item) {
        remove("hist", i)
        p.keys().filter { it.startsWith("pos_" + i.id) || it.startsWith("dur_" + i.id) || it == "last_" + i.id || it == "srv_" + i.id }.forEach { p.remove(it) }
    }

    /** Histórico de "assistir juntos": tira da lista da sala e apaga o ponto salvo em grupo daquele título. */
    fun forgetGroup(i: Item) {
        remove("histg", i)
        p.keys().filter { it.startsWith("pos_g_" + i.id) || it.startsWith("dur_g_" + i.id) }.forEach { p.remove(it) }
    }

    // ---- Continuar de onde parou ----
    fun pos(k: String): Long = p.getLong("pos_$k", 0L)
    /** Salva a posição. Retorna true se o vídeo foi considerado TERMINADO (posição apagada). */
    fun savePos(k: String, pos: Long, dur: Long): Boolean {
        var finished = false
        when {
            dur > 0 && (pos > dur * 0.95 || dur - pos < 90_000) -> { p.remove("pos_$k"); p.remove("dur_$k"); finished = true }
            pos >= 15_000 -> { p.putLong("pos_$k", pos); if (dur > 0) p.putLong("dur_$k", dur) }   // a duração serve para mostrar a % assistida
        }
        return finished
    }
    /** Quanto do título já foi assistido (1 a 99), ou null se não há ponto salvo ou a duração ainda não é conhecida. Séries: vale o episódio em andamento. */
    fun progress(i: Item): Int? {
        val k = (if (i.type == "series") lastEp(i.id) else i.id) ?: return null
        return progressOf(k)
    }

    /** Mesma % para uma chave de posição qualquer (ex.: "off_<id>" nos downloads). */
    fun progressOf(k: String): Int? {
        val pos = pos(k); val dur = p.getLong("dur_$k", 0L)
        if (pos <= 0L || dur <= 0L) return null
        return (pos * 100 / dur).toInt().coerceIn(1, 99)
    }
    // ---- Servidor em que a pessoa estava assistindo (um por título: vale para todos os episódios) ----
    fun saveServer(id: String, addon: String, label: String) {
        val v = JSONObject().put("a", addon).put("l", label).toString()
        if (p.getString("srv_$id") != v) p.putString("srv_$id", v)
    }
    /** @return (nome do addon, rótulo do servidor) ou null se nunca escolheu. */
    fun server(id: String): Pair<String, String>? {
        val raw = p.getString("srv_$id") ?: return null
        return runCatching { JSONObject(raw).let { it.getString("a") to it.getString("l") } }.getOrNull()
    }
    // ---- Quanto cada servidor (host) costuma demorar para o vídeo LARGAR. Aprende com o uso e ordena a fila: abre pelo mais rápido. ----
    fun hostStart(host: String): Long? = p.getLong("hs_$host", 0L).takeIf { it > 0 }
    fun noteHostStart(host: String, ms: Long) {
        val v = ms.coerceAtLeast(1L)
        val old = hostStart(host)
        p.putLong("hs_$host", if (old == null) v else (old + v) / 2)   // média móvel: um dia ruim não condena o servidor para sempre
    }
    // ---- Configurações simples (liga/desliga, números e textos) ----
    fun flag(k: String, d: Boolean): Boolean = p.getLong("f_$k", if (d) 1L else 0L) != 0L
    fun setFlag(k: String, v: Boolean) = p.putLong("f_$k", if (v) 1L else 0L)
    fun num(k: String, d: Long): Long = p.getLong("n_$k", d)
    fun setNum(k: String, v: Long) = p.putLong("n_$k", v)
    fun str(k: String): String? = p.getString("s_$k")
    fun setStr(k: String, v: String?) { if (v == null) p.remove("s_$k") else p.putString("s_$k", v) }
    /** Ponto onde a pessoa parou; 0 se ela desligou "Continuar de onde parou" nos Ajustes. */
    fun resumePos(k: String): Long = if (flag("resume", true)) pos(k) else 0L
    fun clearPositions() { p.keys().filter { it.startsWith("pos_") || it.startsWith("dur_") }.forEach { p.remove(it) } }
    fun clearHostStats() { p.keys().filter { it.startsWith("hs_") }.forEach { p.remove(it) } }
    /** Episódios que a pessoa já assistiu (ids), por série. Fica guardado mesmo depois de terminar a série. */
    fun seenEps(seriesId: String): Set<String> = p.getString("seen_$seriesId")?.split('|')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    fun markSeen(seriesId: String, epId: String) {
        val cur = seenEps(seriesId)
        if (epId !in cur) p.putString("seen_$seriesId", (cur + epId).joinToString("|"))
    }
    fun lastEp(seriesId: String): String? = p.getString("last_$seriesId")
    fun setLastEp(seriesId: String, epId: String) = p.putString("last_$seriesId", epId)
    /** Títulos de episódios já traduzidos (um texto por série/temporada; veja EpTitles.kt). */
    fun epTr(k: String): String? = p.getString(k)
    fun setEpTr(k: String, v: String) = p.putString(k, v)

    fun has(n: String, i: Item) = list(n).any { it.id == i.id && it.type == i.type }
    fun put(n: String, i: Item) = save(n, (listOf(i) + list(n).filter { !(it.id == i.id && it.type == i.type) }).take(20))
    fun remove(n: String, i: Item) = save(n, list(n).filter { !(it.id == i.id && it.type == i.type) })
    fun clear(n: String) = save(n, emptyList())
}

object Api {
    /** Erro de HTTP (guarda o código para decidir se vale tentar outro perfil de acesso). */
    private class HttpErr(val code: Int) : java.io.IOException("HTTP $code")

    /** Faz a chamada e devolve o texto. CANCELÁVEL: se a corrotina é cancelada, a conexão cai na hora. */
    private suspend fun callText(call: okhttp3.Call): String = suspendCancellableCoroutine { k ->
        k.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (k.isActive) k.resumeWith(Result.failure(e)) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val r = runCatching { if (!it.isSuccessful) throw HttpErr(it.code); it.body!!.string() }
                    if (k.isActive) k.resumeWith(r)
                }
            }
        })
    }

    /**
     * Perfis de acesso, do mais comum ao mais "cru". Alguns addons (Cloudflare etc., como o BestCine) respondem ao Stremio
     * mas barram o perfil de navegador (Chrome + Referer): sem o plano B o addon instalava, mas o catálogo vinha vazio.
     */
    private const val PROFILES = 5
    private val hostProfile = ConcurrentHashMap<String, Int>()   // host -> perfil que funcionou (é tentado primeiro da próxima vez)

    private fun profileCall(p: Int, url: String, fast: Boolean): okhttp3.Call {
        val b = Request.Builder().url(url)
        val plain = if (fast) Net.plainFast else Net.plain
        return when (p) {
            0 -> (if (fast) Net.search else Net.api).newCall(b.build())
            1 -> plain.newCall(b.header("User-Agent", "Stremio/1.9.0 (Linux; Android 14)").header("Accept", "application/json").build())
            2 -> plain.newCall(b.header("Accept", "application/json").build())
            3 -> plain.newCall(b.header("User-Agent", Net.UA).header("Accept", "application/json,text/plain,*/*").build())
            else -> plain.newCall(b.header("User-Agent", Net.UA).header("Accept", "application/json")
                .header("Origin", "https://web.stremio.com").header("Referer", "https://web.stremio.com/").build())
        }
    }

    private suspend fun robust(url: String, fast: Boolean): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val first = hostProfile[host]
        val order = (listOfNotNull(first) + (0 until PROFILES)).distinct()
        var last: Exception? = null
        for (p in order) {
            try {
                val txt = callText(profileCall(p, url, fast)).trim().removePrefix("\uFEFF")
                if (txt.startsWith("{") || txt.startsWith("[")) { if (first != p) hostProfile[host] = p; return txt }
                last = java.io.IOException("o servidor devolveu uma página em vez de dados (possível bloqueio)")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                // Só vale trocar de perfil quando o servidor BARROU (401/403/406/…) ou a conexão foi derrubada. Sem internet,
                // lentidão, "não existe" (404) ou limite de uso (429) não mudam com outro perfil.
                val retry = when (e) {
                    is HttpErr -> e.code in 400..499 && e.code != 404 && e.code != 410 && e.code != 429 || (e.code == 503 && !fast)
                    is UnknownHostException, is InterruptedIOException -> false
                    else -> true
                }
                if (!retry) throw e
            }
        }
        throw last ?: IllegalStateException("falha ao acessar o servidor")
    }

    private suspend fun get(url: String): String = robust(url, false)

    /** GET de um addon com os perfis de acesso (Stremio, navegador…) e limite de ~15 s. Devolve o JSON como texto. */
    suspend fun addonText(url: String): String = get(url)

    /** Igual ao get(), mas com limite curto (~4 s por tentativa). */
    private suspend fun getFast(url: String): String = robust(url, true)

    private fun enc(q: String) = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
    /** Ids do Stremio têm ':' (ex.: tt1234567:1:1) — mantemos o ':' e escapamos o resto. */
    private fun pid(id: String) = uriEncode(id, ":")

    private fun motivo(e: Exception): String = when (e) {
        is UnknownHostException -> "sem internet ou endereço do addon não encontrado"
        is InterruptedIOException -> "o servidor demorou demais para responder"
        is SSLException -> "falha na conexão segura (SSL)"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Baixa o manifest tentando 3 jeitos, do mais comum ao mais "cru". Alguns servidores (Cloudflare etc.)
     * recusam o perfil de navegador com Referer, outros só são lentos na primeira chamada.
     * Se tudo falhar, a mensagem diz o MOTIVO real (HTTP 403, timeout, bloqueio…).
     */
    private suspend fun manifestText(u: String): String = withContext(Dispatchers.IO) {
        val tentativas = listOf<() -> okhttp3.Call>(
            { Net.slow.newCall(Request.Builder().url(u).build()) },
            { Net.plain.newCall(Request.Builder().url(u).header("User-Agent", Net.UA).header("Accept", "application/json,text/plain,*/*").build()) },
            { Net.plain.newCall(Request.Builder().url(u).header("Accept", "application/json").build()) },
            // Servidores que liberam só o Stremio (HTTP 403 para navegador/app): se apresenta como Stremio, no celular e no site.
            { Net.plain.newCall(Request.Builder().url(u).header("User-Agent", "Stremio/1.9.0 (Linux; Android 14)").header("Accept", "application/json").build()) },
            { Net.plain.newCall(Request.Builder().url(u).header("User-Agent", Net.UA).header("Accept", "application/json").header("Origin", "https://web.stremio.com").header("Referer", "https://web.stremio.com/").build()) },
            { Net.plain.newCall(Request.Builder().url(u).header("User-Agent", "okhttp/4.12.0").header("Accept", "*/*").build()) },
        )
        val motivos = mutableListOf<String>()
        for (t in tentativas) {
            try {
                val txt = t().execute().use { r ->
                    if (!r.isSuccessful) {
                        val dica = if (r.code != 403) "" else if (r.header("server").equals("cloudflare", true)) " (proteção anti-bot do Cloudflare)" else " (o servidor do addon bloqueou o acesso)"
                        error("o servidor respondeu HTTP ${r.code}$dica")
                    }
                    r.body?.string().orEmpty()
                }.trim().removePrefix("\uFEFF")
                if (txt.startsWith("{")) return@withContext txt
                motivos += "o servidor devolveu uma página em vez do manifest (possível bloqueio)"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                motivos += motivo(e)
            }
        }
        error(motivos.distinct().joinToString(" / "))
    }

    private val CINE_MOVIE = listOf("Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
        "History", "Horror", "Music", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western")
    private val CINE_SERIES = listOf("Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
        "History", "Horror", "Mystery", "Reality-TV", "Romance", "Sci-Fi", "Sport", "Talk-Show", "Thriller", "War", "Western")
    /** Só gera categorias por gênero (browse = false, genreReq = true): o catálogo "puro" do Cinemeta não aparece. */
    private val extraSource: Addon get() = Addon("Cinemeta", CINEMETA, listOf(
        Cat("movie", "top", "Top", false, false, CINE_MOVIE, null, true),
        Cat("series", "top", "Top", false, false, CINE_SERIES, null, true)), false, true)

    private val GENRE_PT = mapOf("Action" to "Ação", "Adventure" to "Aventura", "Animation" to "Animação", "Biography" to "Biografia", "Comedy" to "Comédia",
        "Crime" to "Crime", "Documentary" to "Documentário", "Drama" to "Drama", "Family" to "Família", "Fantasy" to "Fantasia", "History" to "História",
        "Horror" to "Terror", "Music" to "Música", "Mystery" to "Mistério", "Romance" to "Romance", "Sci-Fi" to "Ficção científica", "Sport" to "Esporte",
        "Thriller" to "Suspense", "War" to "Guerra", "Western" to "Faroeste", "Reality-TV" to "Reality", "Talk-Show" to "Talk show")

    fun label(c: Cat): String {
        val g = c.genre?.let { " · " + (GENRE_PT[it] ?: it) } ?: ""
        return if (c.type == "tv") c.name + g   // na aba TV não precisa repetir "TV ·"
        else (if (c.type == "movie") "Filmes" else "Séries") + " · " + c.name + g
    }

    suspend fun addon(link: String): Addon {
        require(link.isNotBlank()) { "cole o link do addon" }
        var u = link.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        if (!u.contains("manifest.json")) u = u.substringBefore("?").trimEnd('/') + "/manifest.json"   // aceita o link do addon sem o manifest.json
        val m = try { JSONObject(manifestText(u)) } catch (e: org.json.JSONException) { error("o manifest veio num formato inválido") }
        val res = m.optJSONArray("resources") ?: JSONArray()
        val names = (0 until res.length()).map { val r = res.get(it); if (r is JSONObject) r.optString("name") else r.toString() }
        val cs = m.optJSONArray("catalogs") ?: JSONArray()
        val cats = (0 until cs.length()).mapNotNull { i ->
            val o = cs.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("type")
            if (t != "movie" && t != "series" && t != "tv") return@mapNotNull null
            var reqOther = false; var searchReq = false; var search = false; var genreReq = false
            val genres = mutableListOf<String>()
            val ex = o.optJSONArray("extra")
            for (j in 0 until (ex?.length() ?: 0)) {
                val e = ex!!.optJSONObject(j) ?: continue
                if (e.optString("name") == "genre") {   // gêneros do catálogo: viram várias categorias
                    val ops = e.optJSONArray("options")
                    for (k in 0 until (ops?.length() ?: 0)) ops!!.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { genres += it }
                    if (e.optBoolean("isRequired")) genreReq = true
                    continue
                }
                val isSearch = e.optString("name") == "search"
                if (isSearch) { search = true; if (e.optBoolean("isRequired")) searchReq = true }
                else if (e.optBoolean("isRequired")) reqOther = true
            }
            val sup = o.optJSONArray("extraSupported")
            for (j in 0 until (sup?.length() ?: 0)) if (sup!!.optString(j) == "search") search = true
            val old = o.optJSONArray("extraRequired")
            for (j in 0 until (old?.length() ?: 0)) { if (old!!.optString(j) == "search") searchReq = true else reqOther = true }
            if (reqOther || (genreReq && genres.isEmpty())) null
            else Cat(t, o.optString("id"), o.optString("name", o.optString("id")), !searchReq && !genreReq, search && !genreReq,
                genres.distinct().take(30), null, genreReq && !searchReq)
        }
        val hasStream = "stream" in names
        val hasSubs = "subtitles" in names
        require(hasStream || hasSubs || cats.isNotEmpty()) { "esse addon não tem catálogo, vídeos nem legendas" }
        // Dados do manifest para a tela de Addons e as regras (types/idPrefixes) de cada recurso.
        val topTypes = jsonStrings(m.optJSONArray("types"))
        val topPrefixes = jsonStrings(m.optJSONArray("idPrefixes"))
        fun rule(name: String): AddonRule {
            for (i in 0 until res.length()) {
                val r = res.get(i)
                if (r is JSONObject && r.optString("name") == name)
                    return AddonRule(jsonStrings(r.optJSONArray("types")).ifEmpty { topTypes }, jsonStrings(r.optJSONArray("idPrefixes")).ifEmpty { topPrefixes })
            }
            return AddonRule(topTypes, topPrefixes)   // recurso escrito só como texto: vale o que o manifest diz no topo
        }
        val info = AddonInfo(
            logo = m.s("logo")?.takeIf { it.startsWith("http") } ?: "", version = m.s("version").orEmpty(), description = m.s("description").orEmpty(),
            types = topTypes, configurable = m.optJSONObject("behaviorHints")?.optBoolean("configurable") ?: false,
            stream = rule("stream"), meta = rule("meta"), subs = rule("subtitles"), rev = AddonInfo.REV)
        return Addon(m.optString("name", "Addon"), u.substringBefore("/manifest.json"), cats, hasStream, "meta" in names, info, hasSubs)
    }

    /**
     * Catálogo com paginação do protocolo Stremio:
     *   /catalog/{type}/{id}.json                       (primeira página)
     *   /catalog/{type}/{id}/skip=20.json               (próximas)
     *   /catalog/{type}/{id}/search=foo&skip=20.json    (busca + skip)
     * O {type} vem SEMPRE da categoria (movie ou series) — nada fixo.
     */
    suspend fun page(a: Addon, c: Cat, q: String? = null, skip: Int = 0): Page {
        val extra = buildList { if (c.genre != null) add("genre=" + enc(c.genre)); if (q != null) add("search=" + enc(q)); if (skip > 0) add("skip=$skip") }
        val path = if (extra.isEmpty()) "" else "/" + extra.joinToString("&")
        val arr = JSONObject(get("${a.url}/catalog/${c.type}/${uriEncode(c.id)}$path.json")).optJSONArray("metas") ?: return Page(emptyList(), 0)
        val items = (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null   // item malformado não derruba o catálogo todo
            Item(o.s("id") ?: "", o.s("type") ?: c.type, o.s("name") ?: "", o.s("poster") ?: o.s("logo"), o.s("background"),
                o.s("description") ?: "", o.s("imdbRating") ?: "", (o.s("releaseInfo") ?: o.s("year") ?: "").take(4))
        }.filter { it.id.isNotEmpty() && it.poster != null && it.title.isNotEmpty() }
        return Page(items, arr.length())
    }

    suspend fun catalog(a: Addon, c: Cat, q: String? = null, skip: Int = 0): List<Item> = page(a, c, q, skip).items

    /**
     * Todas as categorias que dá para mostrar: primeiro os catálogos "de verdade" de cada addon, depois um catálogo por
     * GÊNERO (Ação, Comédia, Terror…), intercalando os addons/catálogos para a lista ter variedade desde o começo.
     * [t] = "movie", "series" ou null (filmes e séries, sem TV).
     */
    fun refs(ads0: List<Addon>, t: String?): List<Ref> {
        // Fonte extra de variedade: o Cinemeta (addon oficial do Stremio) tem catálogo por gênero para filmes e séries.
        val ads = if (ads0.isEmpty() || !Store.flag("extra_cinemeta", true)) ads0 else ads0 + extraSource
        val all = ads.flatMap { a -> a.cats.filter { if (t == null) it.type != "tv" else it.type == t }.map { Ref(a, it) } }
        val base = all.filter { it.c.browse }
        val variants = all.filter { it.c.browse || it.c.genreReq }.map { r -> r.c.genres.map { g -> Ref(r.a, r.c.copy(genre = g)) } }
        val mixed = ArrayList<Ref>()
        var i = 0
        while (variants.any { i < it.size }) { variants.forEach { v -> v.getOrNull(i)?.let { mixed.add(it) } }; i++ }
        return base + mixed
    }

    suspend fun shelves(ads: List<Addon>, t: String?, limit: Int = 10): List<ShelfRow> = coroutineScope {
        refs(ads, t)
            .take(limit)
            .map { r -> async { runCatching { page(r.a, r.c) }.getOrNull()?.let { ShelfRow(label(r.c), it.items, it.raw, r) } } }
            .awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    }

    /**
     * Relê o manifest de cada addon para descobrir os gêneros e catálogos novos (addons já instalados guardam só o que
     * existia na hora de instalar). Roda no máximo a cada 12 h, ou na hora se [force]. Se um addon falhar, mantém o que já tinha.
     * Devolve true se atualizou alguma coisa.
     */
    suspend fun refreshAddons(force: Boolean = false): Boolean = coroutineScope {
        val now = System.currentTimeMillis()
        if (!force && now - Store.manifestAt() < 3L * 3600_000L) return@coroutineScope false
        val old = Store.addons()
        if (old.isEmpty()) return@coroutineScope false
        val fresh = old.map { a -> async { runCatching { addon(a.url + "/manifest.json") }.getOrNull() } }.awaitAll()
        if (fresh.all { it == null }) return@coroutineScope false
        Store.saveAddons(old.mapIndexed { i, a -> fresh[i] ?: a })
        Store.setManifestAt(now)
        true
    }

    internal val infoLock = Any()

    /**
     * Addons instalados antes de o app guardar logo/versão/tipos/legendas: relê SÓ o manifest dos que ainda têm dados antigos.
     * Cada um é salvo assim que chega (um addon lento ou fora do ar não segura nem apaga os outros).
     * Devolve true se atualizou algum.
     */
    suspend fun fillAddonInfo(): Boolean = coroutineScope {
        val missing = Store.addons().filter { it.info.stale }
        missing.map { a ->
            async {
                val f = runCatching { addon(a.url + "/manifest.json") }.getOrNull() ?: return@async false
                synchronized(infoLock) { Store.saveAddons(Store.addons().map { if (it.url == f.url) f else it }) }
                true
            }
        }.awaitAll().any { it }
    }

    /**
     * Aplica sozinho os addons padrão que a pessoa ainda não tem (1ª abertura do app, ou addon novo numa atualização),
     * para ela já abrir e assistir. Cada addon é aplicado UMA vez: se a pessoa remover depois, ele não volta.
     * Os que falham (sem internet, servidor fora do ar) são tentados de novo na próxima abertura.
     * Devolve true se aplicou algum (a Início e o Catálogo recarregam sozinhos: ver Store.onAddonsChanged).
     */
    suspend fun ensureDefaultAddons(defaults: List<Pair<String, String>>): Boolean = coroutineScope {
        fun base(u: String) = u.substringBefore("/manifest.json")
        fun key(u: String) = "auto_" + base(u).replace(Regex("[^A-Za-z0-9]"), "_")
        val have = Store.addons()
        val todo = defaults.filter { (_, u) -> !Store.flag(key(u), false) }
        val (already, missing) = todo.partition { (_, u) -> have.any { it.url == base(u) } }
        already.forEach { (_, u) -> Store.setFlag(key(u), true) }   // já instalado à mão: se remover, não volta
        val got = missing.map { (_, u) -> async { u to runCatching { withTimeoutOrNull(25_000L) { addon(u) } }.getOrNull() } }.awaitAll()
        var added = false
        for ((u, a) in got) {
            if (a == null) continue
            synchronized(infoLock) { val now = Store.addons(); if (now.none { it.url == a.url }) Store.saveAddons(now + a) }
            Store.setFlag(key(u), true)
            added = true
        }
        added
    }

    private const val SEARCH_MS = 4_000L
    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<Item>>>()

    /**
     * Busca de título. Mais rápida: cada catálogo tem limite de 4 s (um addon lento não segura os outros),
     * os resultados vão aparecendo conforme cada addon responde ([onPartial]) e a mesma busca fica em cache por 5 min.
     */
    suspend fun search(ads: List<Addon>, q: String, onPartial: ((List<Item>) -> Unit)? = null): List<Item> = coroutineScope {
        val key = q.trim().lowercase() + "|" + ads.joinToString(",") { it.url }
        val now = System.currentTimeMillis()
        searchCache[key]?.let { (t, l) -> if (now - t < 300_000L) { onPartial?.invoke(l); return@coroutineScope l } }
        val jobs = ads.flatMap { a -> a.cats.filter { it.search && it.type != "tv" }.map { c -> a to c } }.take(6)
        val slots = arrayOfNulls<List<Item>>(jobs.size)
        val lock = Any()
        jobs.mapIndexed { i, (a, c) ->
            async {
                val r = withTimeoutOrNull(SEARCH_MS) { runCatching { catalog(a, c, q) }.getOrDefault(emptyList()) } ?: emptyList()
                if (r.isNotEmpty() && onPartial != null) {
                    val snap = synchronized(lock) { slots[i] = r; slots.filterNotNull().flatten().distinctBy { it.type + it.id } }
                    onPartial(snap)
                } else synchronized(lock) { slots[i] = r }
            }
        }.awaitAll()
        val all = synchronized(lock) { slots.filterNotNull().flatten().distinctBy { it.type + it.id } }
        if (all.isNotEmpty()) {
            if (searchCache.size > 40) searchCache.clear()
            searchCache[key] = System.currentTimeMillis() to all
        }
        all
    }

    /** Meta: consulta todos os addons em paralelo; para séries prefere quem traz o array "videos". */
    private val metaCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    /** Meta com cache de 10 min: abrir os Detalhes já deixa tudo pronto para o Assistir. */
    suspend fun meta(ads: List<Addon>, i: Item): JSONObject? {
        val k = i.type + "|" + i.id
        metaCache[k]?.let { (t, v) -> if (System.currentTimeMillis() - t < 600_000L) return v }
        return metaFetch(ads, i)?.also { m ->
            // A tela abre com o título/sinopse do catálogo (PT-BR) e depois chega o meta, que pode vir em inglês (Cinemeta ou addon em
            // inglês) e trocar tudo de repente. Se o meta não está em português, o nome e a sinopse do catálogo continuam valendo.
            if (!looksPt(m.s("description"))) {
                if (i.title.isNotBlank()) m.put("name", i.title)
                if (looksPt(i.overview)) m.put("description", i.overview)
            }
            metaCache[k] = System.currentTimeMillis() to m
        }
    }

    private val PT_WORDS = setOf("de", "da", "do", "das", "dos", "que", "uma", "um", "com", "para", "por", "em", "no", "na", "os", "as", "seu", "sua", "ele", "ela", "mas", "se", "ao", "é")
    private val EN_WORDS = setOf("the", "and", "of", "to", "in", "is", "his", "her", "with", "for", "who", "when", "that", "an", "on", "after", "must", "from")

    /** Heurística barata: o texto parece estar em português (mais palavras PT que EN)? Vazio = neutro (false). */
    fun looksPt(t: String?): Boolean {
        if (t.isNullOrBlank()) return false
        val w = t.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        val pt = w.count { it in PT_WORDS }
        val en = w.count { it in EN_WORDS }
        return pt > en
    }

    private suspend fun metaFetch(ads: List<Addon>, i: Item): JSONObject? = coroutineScope {
        val ms = ads.filter { it.meta && it.info.meta.ok(i.type, i.id) }.map { a ->
            async { runCatching { JSONObject(get("${a.url}/meta/${i.type}/${pid(i.id)}.json")).optJSONObject("meta") }.getOrNull() }
        }.awaitAll().filterNotNull()
        fun hasVideos(m: JSONObject) = (m.optJSONArray("videos")?.length() ?: 0) > 0
        // Entre os addons, prefere quem traz a sinopse em português; séries: quem traz os episódios.
        val ptFirst = ms.sortedByDescending { looksPt(it.s("description")) }
        val best = if (i.type == "series") ptFirst.firstOrNull { hasVideos(it) } ?: ptFirst.firstOrNull() else ptFirst.firstOrNull()
        val needCm = i.id.startsWith("tt") && (best == null || (i.type == "series" && !hasVideos(best)))
        if (!needCm) return@coroutineScope best
        // O Cinemeta (padrão do Stremio) só responde em inglês: usa para completar episódios/dados, mas o título e a sinopse
        // continuam os do catálogo (PT-BR) sempre que existirem.
        val cm = runCatching { JSONObject(get("$CINEMETA/meta/${i.type}/${pid(i.id)}.json")).optJSONObject("meta") }.getOrNull()
        if (cm == null) return@coroutineScope best
        if (best != null && i.type == "series" && !hasVideos(cm)) return@coroutineScope best
        val out = if (best != null && i.type == "series") best.also { b -> cm.optJSONArray("videos")?.let { b.put("videos", it) } } else cm
        if (i.title.isNotBlank() && !looksPt(out.s("description")) ) {
            // Título do catálogo (PT-BR) vale mais que o do Cinemeta.
            if (best == null || best.s("name") == null) out.put("name", i.title)
            if (i.overview.isNotBlank() && looksPt(i.overview)) out.put("description", i.overview)
            else if (best?.s("description") != null) out.put("description", best.s("description"))
        }
        out
    }
    private const val CINEMETA = "https://v3-cinemeta.strem.io"

    private val castCache = ConcurrentHashMap<String, List<CastMember>>()

    /** Elenco com foto no formato do Cinemeta (app_extras.cast ou credits_cast). */
    private fun castWithPhotos(m: JSONObject?): List<CastMember> {
        if (m == null) return emptyList()
        val out = mutableListOf<CastMember>()
        m.optJSONObject("app_extras")?.optJSONArray("cast")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out += CastMember(o.s("name") ?: continue, o.s("photo").orEmpty())
            }
        }
        if (out.isEmpty()) m.optJSONArray("credits_cast")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val pp = o.s("profile_path")
                out += CastMember(o.s("name") ?: continue, if (pp == null) "" else if (pp.startsWith("http")) pp else "https://image.tmdb.org/t/p/w185$pp")
            }
        }
        return out
    }

    /** Só os nomes (sem foto): campo "cast" ou links de categoria Cast. */
    private fun castNames(m: JSONObject?): List<CastMember> {
        if (m == null) return emptyList()
        val out = mutableListOf<CastMember>()
        m.optJSONArray("cast")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { out += CastMember(it, "") } }
        m.optJSONArray("links")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("category").equals("cast", true)) o.s("name")?.let { out += CastMember(it, "") }
            }
        }
        return out
    }

    /**
     * Elenco do título (até 16 pessoas), com foto quando existe. Usa o meta do addon; se ele não traz fotos,
     * busca no Cinemeta (títulos com id do IMDb). Sem foto em lugar nenhum, devolve só os nomes.
     */
    suspend fun cast(m: JSONObject?, item: Item): List<CastMember> {
        val k = item.type + "|" + item.id
        castCache[k]?.let { return it }
        var l = castWithPhotos(m)
        if (l.none { it.photo.isNotBlank() } && item.id.startsWith("tt")) {
            val cm = withTimeoutOrNull(8_000L) { runCatching { JSONObject(get("$CINEMETA/meta/${item.type}/${pid(item.id)}.json")).optJSONObject("meta") }.getOrNull() }
            val c2 = castWithPhotos(cm).ifEmpty { castNames(cm) }
            if (c2.isNotEmpty()) l = c2
        }
        if (l.isEmpty()) l = castNames(m)
        l = l.distinctBy { it.name.lowercase() }.take(16)
        if (l.isNotEmpty()) castCache[k] = l
        return l
    }

    /** Episódios do meta. Se um vídeo vier sem "id", monta o formato do protocolo: tt1234567:temporada:episódio. */
    fun eps(m: JSONObject?, seriesId: String): List<Ep> {
        val v = m?.optJSONArray("videos") ?: return emptyList()
        val all = (0 until v.length()).mapNotNull {
            val o = v.optJSONObject(it) ?: return@mapNotNull null
            val season = o.optInt("season", -1)
            val n = o.optInt("episode", o.optInt("number", 0))
            if (season < 0 || n <= 0) return@mapNotNull null
            val id = o.s("id") ?: "$seriesId:$season:$n"
            Ep(id, season, n, o.s("title") ?: o.s("name") ?: "Episódio $n")
        }.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
        return all.filter { it.season > 0 }.ifEmpty { all }   // temporada 0 (extras) só se for tudo que existe
    }

    /**
     * Servidores de UM addon para um título/episódio. Nunca lança erro:
     *  1) cache local com menos de 2 h → devolve na hora, SEM requisição ao addon;
     *  2) sem cache / vencido → busca na internet (limite ~4,5 s) e grava o resultado novo;
     *  3) addon com erro (HTTP 404/500…, timeout, sem rede, JSON quebrado) → lista vazia, e os outros addons seguem.
     * @return (servidores, true se vieram do cache)
     */
    private suspend fun streamsCached(a: Addon, type: String, sid: String, useCache: Boolean): Pair<List<Stream>, Boolean> {
        val cacheable = type != "tv"   // canal ao vivo: link costuma ter validade curta, não guarda
        if (useCache && cacheable) {
            StreamCache.fresh(sid, a.url)?.let { raw ->
                val l = runCatching { parseStreams(raw) }.getOrNull()
                if (!l.isNullOrEmpty()) return l to true
            }
        }
        return try {
            val raw = getFast("${a.url}/stream/$type/${pid(sid)}.json")
            val l = parseStreams(raw)
            if (cacheable && l.isNotEmpty()) StreamCache.save(sid, a.url, raw)   // resposta vazia/falha nunca vira cache
            l to false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // cancelamento/timeout da busca precisa continuar subindo
        } catch (e: Exception) {
            emptyList<Stream>() to false
        }
    }

    suspend fun streams(a: Addon, type: String, sid: String): List<Stream> = streamsCached(a, type, sid, true).first

    private fun parseStreams(json: String): List<Stream> {
        val arr = JSONObject(json).optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val label = listOfNotNull(o.s("name"), o.s("title") ?: o.s("description")).joinToString(" ").replace("\n", " ").trim()
            // Headers que o próprio addon exige para tocar (Referer, User-Agent, Cookie…)
            val req = o.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            val hdr = req?.let { r -> r.keys().asSequence().associateWith { k -> r.optString(k) }.filterValues { v -> v.isNotEmpty() } } ?: emptyMap()
            Stream(label.ifEmpty { "Opção ${it + 1}" }, o.s("url"), o.s("externalUrl"), hdr)
        }
    }

    // ---------------------------------------------------------------------------------
    //  FILTRO PARALELO: testa TODOS os links ao mesmo tempo (máx. 2,2 s) e remove os mortos/lentos
    // ---------------------------------------------------------------------------------
    private const val PROBE_MS = 2200L

    /** Resposta 2xx que na verdade é página de erro (HTML/JSON) ou playlist HLS vazia/inválida NÃO é vídeo: não pode passar no teste. */
    private fun validMedia(url: String, r: okhttp3.Response, head: ByteArray): Boolean {
        val ct = r.header("Content-Type")?.lowercase().orEmpty()
        if (ct.startsWith("text/html") || ct.contains("json") || (ct.contains("xml") && !ct.contains("dash"))) return false
        if (head.isEmpty()) return false
        val hls = ct.contains("mpegurl") || url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        return !hls || String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")
    }

    /** @return latência em ms se o link responde a tempo, ou null se está morto/lento. */
    private fun probe(s: Stream): Long? {
        val u = s.url ?: return null
        val t0 = System.nanoTime()
        fun spent() = (System.nanoTime() - t0) / 1_000_000
        return try {
            fun req() = Request.Builder().url(u).also { b -> s.headers.forEach { (k, v) -> b.header(k, v) } }
            var invalid = false
            // 1) Teste "de verdade": pede os primeiros 16 KB e confere que é vídeo/playlist (não página de erro com status 200).
            val real = runCatching {
                Net.probeClient(PROBE_MS).newCall(req().header("Range", "bytes=0-16383").build()).execute().use { r ->
                    if (r.code in 200..299) {
                        val head = r.peekBody(16384).bytes()
                        if (validMedia(u, r, head)) spent() else { invalid = true; null }
                    } else null
                }
            }.getOrNull()
            if (real != null) return real
            if (invalid) return null
            // Estourou o tempo no 1º teste: servidor lento = descartado (antes gastava mais 2,5 s tentando de novo).
            val left = PROBE_MS - spent()
            if (left < 300) return null
            var code = Net.probeClient(left).newCall(req().head().build()).execute().use { it.code }
            // Muitos servidores recusam HEAD (400/403/405/501) mas tocam normalmente: 2ª tentativa barata (1 byte), dentro do mesmo orçamento.
            if ((code == 400 || code == 403 || code == 405 || code == 501) && PROBE_MS - spent() > 400)
                code = Net.probeClient(PROBE_MS - spent()).newCall(req().header("Range", "bytes=0-0").build()).execute().use { it.code }
            if (code in 200..399) spent() else null
        } catch (e: Exception) { null }
    }

    suspend fun validate(l: List<Pair<String, Stream>>): List<Pair<String, Stream>> = coroutineScope {
        val direct = l.filter { it.second.url != null }
        val alive = direct.map { p -> async(Dispatchers.IO) { probe(p.second)?.let { ms -> p to ms } } }
            .awaitAll().filterNotNull().sortedBy { it.second }.map { it.first }   // mais rápidos primeiro
        val external = l.filter { it.second.url == null && it.second.external != null }
        // Se NADA passou no teste (ex.: rede instável), não deixa o usuário sem opção: devolve a lista original.
        if (alive.isEmpty() && direct.isNotEmpty()) direct + external else alive + external
    }

    /**
     * Índice (dentro de [l]) do servidor que a pessoa já estava usando, ou -1.
     * O rótulo pode variar entre episódios (número, tamanho do arquivo…), então se não houver igual
     * procura no mesmo addon um rótulo com as mesmas letras.
     */
    fun preferred(l: List<Pair<String, Stream>>, pref: Pair<String, String>?): Int {
        if (pref == null) return -1
        fun norm(t: String) = t.lowercase().filter { it.isLetter() }
        val ok = l.withIndex().filter { it.value.second.url != null && it.value.first == pref.first }
        return ok.firstOrNull { it.value.second.label == pref.second }?.index
            ?: ok.firstOrNull { norm(it.value.second.label) == norm(pref.second) }?.index
            ?: -1
    }

    // ---------------------------------------------------------------------------------
    //  SERVIDORES AO VIVO: cada link que passa no teste entra na lista NA HORA (o app já toca o 1º).
    //  Nada espera o mais lento: addon lento é cortado em 4 s; se nada passou no teste em 1 s, entrega os
    //  links sem testar (o player tenta e pula sozinho para o próximo). Resultado fica em cache por 3 min,
    //  então abrir os Detalhes já "esquenta" a lista e o Assistir fica instantâneo.
    // ---------------------------------------------------------------------------------
    private const val ADDON_MS = 4_500L
    private const val FALLBACK_MS = 2000L
    private const val TOTAL_MS = 7_000L
    private const val SESSION_TTL = 180_000L
    private val sessions = ConcurrentHashMap<String, StreamSession>()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun streamSession(ads: List<Addon>, type: String, sid: String): StreamSession {
        val key = "$type|$sid|${ads.count { it.stream }}"
        val now = System.currentTimeMillis()
        sessions[key]?.let { s -> if (!s.done || (s.items.isNotEmpty() && now - s.finishedAt < SESSION_TTL)) return s }
        if (sessions.size > 12) sessions.entries.removeIf { it.value.done && now - it.value.finishedAt > SESSION_TTL }
        val s = StreamSession()
        sessions[key] = s
        bg.launch {
            try {
                // true = os links vieram do cache e nenhum respondeu (provavelmente venceram): refaz pela internet, ignorando o cache
                if (runSession(s, ads, type, sid, useCache = true)) runSession(s, ads, type, sid, useCache = false)
            } finally { s.finishedAt = System.currentTimeMillis(); s.done = true }
        }
        return s
    }

    private suspend fun runSession(s: StreamSession, ads: List<Addon>, type: String, sid: String, useCache: Boolean): Boolean {
        val fromCache = java.util.concurrent.atomic.AtomicBoolean(false)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val untested = CopyOnWriteArrayList<Pair<String, Stream>>()
        val lastResort = CopyOnWriteArrayList<Pair<String, Stream>>()   // links de hosts que falharam várias vezes: só se não sobrar nada
        withTimeoutOrNull(TOTAL_MS) {
            kotlinx.coroutines.coroutineScope {
                launch {
                    delay(FALLBACK_MS)
                    // Só libera link NÃO testado se, depois de ~2,8 s, nenhum passou no teste (antes liberava em 0,6 s e o player
                    // começava num link morto, esperando vários segundos para pular).
                    // Links do cache só entram se passarem no teste: se vencerem, a sessão refaz a busca pela internet.
                    if (!fromCache.get()) {
                        if (s.items.isEmpty()) untested.forEach { s.add(it) }
                        if (s.items.isEmpty()) lastResort.forEach { s.add(it) }
                    }
                }
                ads.filter { it.stream && it.info.stream.ok(type, sid) }.forEach { a ->
                    launch {
                        val (l, cached) = withTimeoutOrNull(ADDON_MS) { streamsCached(a, type, sid, useCache) } ?: (emptyList<Stream>() to false)
                        if (cached) fromCache.set(true)
                        for (st in l) {
                            val u = st.url ?: continue          // torrent/link externo não toca no app
                            if (type != "tv" && !isPtBr(st.label)) continue   // só PT-BR: nem gasta rede testando os outros idiomas
                            if (!seen.add(u)) continue
                            val p = a.name to st
                            if (Net.isBad(u)) { lastResort += p; continue }   // host que vive falhando: nem gasta rede, fica de reserva
                            untested += p
                            if (!fromCache.get() && s.items.isEmpty() && System.currentTimeMillis() - s.startedAt > FALLBACK_MS) s.add(p)
                            launch { if (probe(st) != null) { Net.markGood(u); s.add(p) } else Net.markBad(u) }
                        }
                    }
                }
            }
        }
        if (s.items.isEmpty()) {
            if (fromCache.get()) return true   // veio do cache e nada tocou: quem chamou refaz sem cache
            // Rede instável: nada passou no teste, mas há links. Não deixa a pessoa sem opção.
            untested.forEach { s.add(it) }
            if (s.items.isEmpty()) lastResort.forEach { s.add(it) }
        }
        return false
    }

    /** Busca streams em todos os addons ao mesmo tempo, tira duplicados/torrents e valida em paralelo. */
    suspend fun allStreams(ads: List<Addon>, type: String, sid: String): List<Pair<String, Stream>> = coroutineScope {
        val all = ads.filter { it.stream && it.info.stream.ok(type, sid) }
            .map { a -> async { runCatching { streams(a, type, sid) }.getOrDefault(emptyList()).map { a.name to it } } }
            .awaitAll().flatten()
            .filter { it.second.url != null || it.second.external != null }   // torrent puro não toca no app
            .distinctBy { it.second.url ?: it.second.external }
        validate(all)
    }
}
