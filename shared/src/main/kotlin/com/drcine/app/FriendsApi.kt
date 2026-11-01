package com.drcine.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Uma pessoa. [rel] só vem na busca: "none", "sent" (pedido enviado), "received" (ela pediu a você) ou "friend". [status] = "online", "dnd" (não perturbe), "offline" ou "" (não se sabe). */
data class Person(val id: String, val name: String, val avatar: String, val rel: String = "", val color: String = "", val status: String = "")

/** Amigos, pedidos recebidos/enviados e se o meu histórico está visível para os amigos. */
data class FriendsData(val friends: List<Person>, val incoming: List<Person>, val outgoing: List<Person>, val share: Boolean)

/** Um título que o amigo assistiu. */
data class SharedItem(val id: String, val type: String, val title: String, val poster: String?, val at: Long)

/**
 * Perfil completo de uma pessoa. [rel] = "me", "friend", "sent", "received" ou "none".
 * [banner] = "" (padrão), "g0".."g7" (degradê pronto) ou endereço de uma foto. [color] = "#RRGGBB" do nome ("" = branco).
 * [friendsCount] = -1 quando a pessoa deixou a lista de amigos privada.
 */
data class Profile(
    val id: String, val name: String, val avatar: String, val bio: String, val color: String, val banner: String,
    val rel: String, val showFriends: Boolean, val share: Boolean, val friendsCount: Int, val friends: List<Person>,
    /** Como a pessoa aparece agora ("online", "dnd", "offline"; "" = não visível para você). */
    val status: String = "",
    /** Só no meu perfil: o que EU escolhi ("online", "offline" ou "dnd"). */
    val statusPref: String = "",
    /** Estatísticas: quando entrou no app (ms, 0 = não se sabe), tempo com o app aberto e tempo assistindo filmes/séries (segundos). */
    val joinedAt: Long = 0L,
    val appSecs: Long = 0L,
    val watchMovieSecs: Long = 0L,
    val watchSeriesSecs: Long = 0L,
    /** Tempo assistindo vídeos do YouTube (segundos). Vem do amigos11.sql (0 sem ele). */
    val watchYtSecs: Long = 0L,
    /** Tempo assistindo canais de TV ao vivo (segundos). Vem do amigos12.sql (0 sem ele). */
    val watchTvSecs: Long = 0L,
    /** Última vez online (ms). 0 = escondida (só eu e os amigos veem; quem aparece offline não mostra). */
    val lastSeen: Long = 0L,
    /** Só entre amigos: o painel "vocês dois". */
    val together: Together? = null,
    /** Filme ou série favorito que a pessoa escolheu para mostrar no perfil (null = não escolheu). Vem do amigos13.sql. */
    val fav: Favorite? = null,
)

/** Filme ou série favorito do perfil. [type] = "movie" ou "series"; [poster] pode ser null. */
data class Favorite(val id: String, val type: String, val title: String, val poster: String?)

/** O que dois amigos já assistiram juntos na sala (segundos; [ytSecs] = YouTube, [tvSecs] = TV ao vivo), a sessão mais longa e desde quando são amigos ([since], ms). */
data class Together(val movieSecs: Long, val seriesSecs: Long, val longestSecs: Long, val since: Long, val ytSecs: Long = 0L, val tvSecs: Long = 0L)

/** Convite de um amigo para entrar numa sala do Assistir juntos. */
data class RoomInvite(val from: Person, val code: String, val at: Long)

/** Uma mensagem do chat privado. [kind] = "text" ou "share" (recomendação de filme/série: ver [itemId], [itemType], [itemTitle], [itemPoster]). */
data class DmMsg(
    val id: Long, val mine: Boolean, val body: String, val kind: String,
    val itemId: String, val itemType: String, val itemTitle: String, val itemPoster: String?, val at: Long,
    /** Resposta citada (arrastar a mensagem para o lado). Vem de dentro da mensagem criptografada. */
    val reply: DmReply? = null,
    /** true = não deu para abrir esta mensagem neste aparelho (veio de outro aparelho ou de outra chave). */
    val locked: Boolean = false,
)

/** A mensagem que está sendo respondida: [id] dela, [name] de quem escreveu e um trecho do [text]. */
data class DmReply(val id: Long, val name: String, val text: String)

/** Uma conversa na lista: o amigo, a prévia da última mensagem e quantas ainda não li. [lastBody]/[lastKind] são o que o servidor guarda (texto embaralhado). */
data class Convo(
    val person: Person, val last: String, val lastAt: Long, val lastMine: Boolean, val unread: Int,
    val lastBody: String = "", val lastKind: String = "text",
)

/** Mensagens de uma conversa + o status atual do amigo. */
/** [live] = números das mensagens que ainda existem no servidor e [upto] = o maior número que já existia (null = servidor antigo, sem apagar). */
/** [readUpto] = a última mensagem MINHA que o amigo já viu (0 = nenhuma, ou servidor sem o amigos10). */
data class DmThread(val status: String, val msgs: List<DmMsg>, val live: List<Long>? = null, val upto: Long = 0L, val readUpto: Long = 0L)

/** Histórico de um amigo. [hidden] = ele escolheu não mostrar. */
/** [items] = filmes e séries; [yt] = vídeos do YouTube (id = id do vídeo; só vem com o amigos11.sql). */
data class FriendHistory(val hidden: Boolean, val items: List<SharedItem>, val yt: List<SharedItem> = emptyList(), val tv: List<SharedItem> = emptyList())

/**
 * Amigos (perfil público, pedidos e histórico compartilhado). Tudo passa por funções do Supabase que conferem quem está
 * pedindo; o SQL está em amigos.sql (passo 4i do CONFIGURAR_CONTA.md).
 */
object FriendsApi {
    private val JSON = "application/json".toMediaType()

    private suspend fun rpc(fn: String, body: JSONObject, token: String, missing: String? = null): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + "/rest/v1/rpc/$fn")
            .header("apikey", CloudConfig.ANON_KEY).header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON)).build()
        val (c, b) = try {
            Net.api.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            -1 to (e.message ?: "")
        }
        if (c in 200..299) b else fail(c, b, missing)
    }

    private fun fail(c: Int, body: String, missing: String? = null): Nothing {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val msg = o?.optString("message").orEmpty()
        val code = o?.optString("code").orEmpty()
        throw AuthError(
            when {
                c == -1 -> "Sem conexão com o servidor. Confira sua internet."
                c == 404 || code == "PGRST202" || body.contains("Could not find the function") ->
                    missing ?: "Os amigos ainda não foram ligados no Supabase: rode o SQL amigos.sql (passo 4i do CONFIGURAR_CONTA.md)."
                c == 401 || code == "42501" && msg.startsWith("Entre") -> "Entre na sua conta de novo."
                c == 429 -> "Muitas tentativas. Espere um minuto."
                code == "P0001" && msg.isNotBlank() -> msg   // aviso escrito de propósito no SQL (ex.: "Pessoa não encontrada.")
                else -> "Não deu certo agora (erro $c). Tente de novo em instantes."
            }, c
        )
    }

    private fun person(o: JSONObject) = Person(o.optString("id"), o.optString("name").ifBlank { "Usuário" }, o.optString("avatar"), o.optString("rel"), o.optString("color"), o.optString("status"))
    private fun people(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::person) }

    /** Copia nome e foto da conta para o perfil público (é o que as outras pessoas encontram na busca). */
    suspend fun syncProfile(token: String) { rpc("profile_sync", JSONObject(), token) }

    suspend fun search(token: String, q: String): List<Person> {
        val raw = rpc("friend_search", JSONObject().put("p_q", q.trim()), token)   // fora do runCatching: o aviso de erro precisa chegar à tela
        return people(runCatching { JSONArray(raw) }.getOrNull())
    }

    /** Devolve "sent" (pedido enviado), "friend" (a pessoa já tinha pedido: viraram amigos) ou "exists". */
    suspend fun request(token: String, id: String): String =
        rpc("friend_request", JSONObject().put("p_target", id), token).trim().trim('"')

    suspend fun respond(token: String, id: String, accept: Boolean) {
        rpc("friend_respond", JSONObject().put("p_requester", id).put("p_accept", accept), token)
    }

    /** Desfaz a amizade ou cancela um pedido enviado. */
    suspend fun remove(token: String, id: String) { rpc("friend_remove", JSONObject().put("p_other", id), token) }

    suspend fun list(token: String): FriendsData {
        val raw = rpc("friend_list", JSONObject(), token)
        val o = runCatching { JSONObject(raw) }.getOrElse { throw AuthError("Resposta inesperada do servidor. Tente de novo.") }
        return FriendsData(people(o.optJSONArray("friends")), people(o.optJSONArray("incoming")), people(o.optJSONArray("outgoing")), o.optBoolean("share", true))
    }

    /** O que cada amigo está assistindo agora (id da pessoa -> título). Vazio se o SQL amigos9 ainda não foi rodado. */
    suspend fun watching(token: String): Map<String, String> {
        val o = JSONObject(rpc("friends_watching", JSONObject(), token))
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    }

    suspend fun setShare(token: String, on: Boolean) { rpc("profile_share", JSONObject().put("p_on", on), token) }

    /** Conta ao servidor que a pessoa está assistindo [i] (vai para o histórico que os amigos veem). Falha em silêncio. */
    suspend fun push(token: String, i: Item) {
        runCatching { rpc("history_push", JSONObject().put("p_id", i.id).put("p_type", i.type).put("p_title", i.title).put("p_poster", i.poster ?: ""), token) }
    }

    suspend fun history(token: String, friendId: String): FriendHistory {
        val raw = rpc("friend_history", JSONObject().put("p_friend", friendId), token)
        val o = runCatching { JSONObject(raw) }.getOrElse { throw AuthError("Resposta inesperada do servidor. Tente de novo.") }
        fun rows(a: JSONArray?): List<SharedItem> = (0 until (a?.length() ?: 0)).mapNotNull { n ->
            val i = a?.optJSONObject(n) ?: return@mapNotNull null
            SharedItem(i.optString("id"), i.optString("type"), i.optString("title"), i.optString("poster").takeIf { it.isNotBlank() }, i.optLong("at"))
        }
        return FriendHistory(o.optBoolean("hidden", false), rows(o.optJSONArray("items")), rows(o.optJSONArray("yt")), rows(o.optJSONArray("tv")))
    }

    // ---- Perfil completo (bio, banner, cor do nome, lista de amigos) -------------------------------------------------

    suspend fun profile(token: String, id: String): Profile {
        val raw = rpc("profile_get", JSONObject().put("p_user", id), token)
        val o = runCatching { JSONObject(raw) }.getOrElse { throw AuthError("Resposta inesperada do servidor. Tente de novo.") }
        return Profile(
            o.optString("id"), o.optString("name").ifBlank { "Usuário" }, o.optString("avatar"), o.optString("bio"), o.optString("color"),
            o.optString("banner"), o.optString("rel"), o.optBoolean("show_friends", true), o.optBoolean("share", true),
            o.optInt("friends_count", -1), people(o.optJSONArray("friends")),
            o.optString("status"), o.optString("status_pref"),
            o.optLong("joined_at"), o.optLong("app_secs"), o.optLong("watch_movie_secs"), o.optLong("watch_series_secs"), o.optLong("watch_yt_secs"), o.optLong("watch_tv_secs"), o.optLong("last_seen"),
            o.optJSONObject("together")?.let { t -> Together(t.optLong("movie_secs"), t.optLong("series_secs"), t.optLong("longest_secs"), t.optLong("since"), t.optLong("yt_secs"), t.optLong("tv_secs")) },
            fav = o.optJSONObject("fav")?.let { f ->
                f.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                    Favorite(id, f.optString("type", "movie"), f.optString("title"), f.optString("poster").takeIf { it.isNotBlank() })
                }
            },
        )
    }

    /** Soma [secs] de vídeo realmente tocado ao tempo assistido do perfil ([type] = "movie", "series", "youtube" ou "tv"). Falha em silêncio (precisa do amigos7.sql; "youtube" precisa do amigos11.sql; "tv" precisa do amigos12.sql). */
    suspend fun watchTick(token: String, secs: Int, type: String) {
        runCatching { rpc("watch_tick", JSONObject().put("p_secs", secs).put("p_type", type), token) }
    }

    /** Em sala, com o vídeo tocando: soma o tempo assistido junto com o amigo [friendId]. Falha em silêncio (e ignora quem não é amigo). */
    suspend fun togetherTick(token: String, friendId: String, secs: Int, type: String) {
        runCatching { rpc("together_tick", JSONObject().put("p_friend", friendId).put("p_secs", secs).put("p_type", type), token) }
    }

    /** Define o filme/série favorito do perfil ([item] = null apaga). Precisa do amigos13.sql (passo 4u do CONFIGURAR_CONTA.md). */
    suspend fun setFavorite(token: String, item: Item?) {
        val o = JSONObject()
            .put("p_id", item?.id ?: "")
            .put("p_type", if (item?.type == "series") "series" else "movie")
            .put("p_title", item?.title ?: "")
            .put("p_poster", item?.poster ?: "")
        rpc("profile_fav_set", o, token, "Falta rodar o SQL amigos13.sql no Supabase (passo 4u do CONFIGURAR_CONTA.md).")
    }

    suspend fun editProfile(token: String, bio: String, color: String, banner: String, showFriends: Boolean) {
        rpc(
            "profile_edit",
            JSONObject().put("p_bio", bio.trim()).put("p_color", color).put("p_banner", banner).put("p_show_friends", showFriends),
            token,
        )
    }

    private fun bannerPath(userId: String) = "/storage/v1/object/banners/$userId.jpg"

    /** Envia a foto do banner (JPEG já reduzida) e devolve o endereço dela. Precisa do SQL amigos2.sql (passo 4j). */
    suspend fun uploadBanner(token: String, userId: String, jpeg: ByteArray): String {
        val (code, body) = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + bannerPath(userId))
                .header("apikey", CloudConfig.ANON_KEY).header("Authorization", "Bearer $token").header("x-upsert", "true")
                .post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()
            try { Net.api.newCall(req).execute().use { it.code to (it.body?.string() ?: "") } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { -1 to (e.message ?: "") }
        }
        if (code == -1) throw AuthError("Sem conexão com o servidor. Confira sua internet.", -1)
        if (code !in 200..299) throw AuthError(
            if (code == 404 || code == 400 || code == 401 || code == 403)
                "O Supabase não deixou enviar o banner: rode o SQL amigos2.sql (passo 4j do CONFIGURAR_CONTA.md)."
            else "Não consegui enviar o banner (erro $code).", code,
        )
        return CloudConfig.URL.trimEnd('/') + "/storage/v1/object/public/banners/$userId.jpg?v=${System.currentTimeMillis()}"
    }

    suspend fun removeBannerFile(token: String, userId: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + bannerPath(userId))
                .header("apikey", CloudConfig.ANON_KEY).header("Authorization", "Bearer $token").delete().build()
            runCatching { Net.api.newCall(req).execute().close() }
        }
    }

    // ---- Fotos e áudios do chat (o arquivo já chega aqui criptografado pelo celular) ---------------------------------
    private const val MISSING_MEDIA = "O Supabase não deixou enviar a mídia: rode o SQL amigos8.sql (passo 4p do CONFIGURAR_CONTA.md)."

    /** Sobe [blob] (já criptografado) para o bucket privado chat-media em [path] = "<id da conta>/<código>.bin". */
    suspend fun mediaUpload(token: String, path: String, blob: ByteArray) {
        val code = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + "/storage/v1/object/chat-media/$path")
                .header("apikey", CloudConfig.ANON_KEY).header("Authorization", "Bearer $token")
                .post(blob.toRequestBody("application/octet-stream".toMediaType())).build()
            try { Net.api.newCall(req).execute().use { it.code } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { -1 }
        }
        if (code == -1) throw AuthError("Sem conexão com o servidor. Confira sua internet.", -1)
        if (code !in 200..299) throw AuthError(
            if (code == 404 || code == 400 || code == 401 || code == 403) MISSING_MEDIA else "Não consegui enviar o arquivo (erro $code).", code,
        )
    }

    /** Baixa o arquivo criptografado de [path]. */
    suspend fun mediaDownload(token: String, path: String): ByteArray {
        val (code, bytes) = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + "/storage/v1/object/authenticated/chat-media/$path")
                .header("apikey", CloudConfig.ANON_KEY).header("Authorization", "Bearer $token").get().build()
            try { Net.api.newCall(req).execute().use { it.code to (if (it.isSuccessful) it.body?.bytes() else null) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { -1 to null }
        }
        if (code == -1) throw AuthError("Sem conexão com o servidor. Confira sua internet.", -1)
        if (code !in 200..299 || bytes == null) throw AuthError(if (code == 404) "Este arquivo não existe mais." else "Não consegui baixar o arquivo (erro $code).", code)
        return bytes
    }

    /** Apaga o arquivo (só funciona nos que são meus). Falha em silêncio. */
    suspend fun mediaDelete(token: String, path: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + "/storage/v1/object/chat-media/$path")
                .header("apikey", CloudConfig.ANON_KEY).header("Authorization", "Bearer $token").delete().build()
            runCatching { Net.api.newCall(req).execute().close() }
        }
    }

    // ---- Convites para a sala ------------------------------------------------------------------------------------------

    suspend fun inviteSend(token: String, friendId: String, code: String) {
        rpc("invite_send", JSONObject().put("p_friend", friendId).put("p_code", code), token)
    }

    suspend fun invites(token: String): List<RoomInvite> {
        val raw = rpc("invite_inbox", JSONObject(), token)
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { n ->
            val o = a.optJSONObject(n) ?: return@mapNotNull null
            val code = o.optString("code")
            if (code.isBlank()) null else RoomInvite(person(o), code, o.optLong("at"))
        }
    }

    suspend fun inviteDismiss(token: String, fromId: String) {
        runCatching { rpc("invite_dismiss", JSONObject().put("p_from", fromId), token) }
    }

    // ---- Status (online / offline / não perturbe) e chat privado ---------------------------------------------------------

    private const val MISSING_CHAT = "O chat ainda não foi ligado no Supabase: rode o SQL amigos3.sql (passo 4k do CONFIGURAR_CONTA.md)."

    /** "Estou com o app aberto": o app chama a cada ~30 s. Falha em silêncio. */
    suspend fun beat(token: String) {
        runCatching { rpc("presence_beat", JSONObject(), token, MISSING_CHAT) }
    }

    /** [status] = "online", "offline" ou "dnd" (não perturbe). */
    suspend fun setStatus(token: String, status: String) {
        rpc("status_set", JSONObject().put("p_status", status), token, MISSING_CHAT)
    }

    private const val MISSING_E2E = "A criptografia do chat ainda não foi ligada no Supabase: rode o SQL amigos4.sql (passo 4l do CONFIGURAR_CONTA.md)."

    /** Publica a minha chave PÚBLICA de criptografia (a privada nunca sai do celular). Precisa do SQL amigos4.sql (passo 4l). */
    suspend fun e2eKeySet(token: String, key: String) {
        rpc("e2e_key_set", JSONObject().put("p_key", key), token, MISSING_E2E)
    }

    /** Chave pública de criptografia de um amigo ("" = ele ainda não ativou). */
    suspend fun e2eKeyGet(token: String, userId: String): String {
        val raw = rpc("e2e_key_get", JSONObject().put("p_user", userId), token, MISSING_E2E).trim()
        if (raw.isEmpty() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrDefault("")
    }

    private const val MISSING_PUSH = "As notificações ainda não foram ligadas no Supabase: rode o SQL amigos5.sql (passo 4m do CONFIGURAR_CONTA.md)."

    /** Registra este celular (endereço do Firebase) para receber notificações em tempo real. Precisa do SQL amigos5.sql (passo 4m). */
    suspend fun pushTokenSet(token: String, fcmToken: String) {
        rpc("push_token_set", JSONObject().put("p_token", fcmToken), token, MISSING_PUSH)
    }

    suspend fun pushTokenClear(token: String, fcmToken: String) {
        runCatching { rpc("push_token_clear", JSONObject().put("p_token", fcmToken), token, MISSING_PUSH) }
    }

    /** Manda um texto ([kind] = "text") ou recomenda um filme/série ([kind] = "share") para um amigo. */
    suspend fun dmSend(
        token: String, friendId: String, body: String, kind: String = "text",
        itemId: String = "", itemType: String = "", itemTitle: String = "", itemPoster: String = "",
    ) {
        rpc(
            "dm_send",
            JSONObject().put("p_to", friendId).put("p_body", body).put("p_kind", kind).put("p_item_id", itemId)
                .put("p_item_type", itemType).put("p_item_title", itemTitle).put("p_item_poster", itemPoster),
            token, MISSING_CHAT,
        )
    }

    private fun dmMsg(o: JSONObject) = DmMsg(
        o.optLong("id"), o.optBoolean("mine"), o.optString("body"), o.optString("kind", "text"),
        o.optString("item_id"), o.optString("item_type"), o.optString("item_title"),
        o.optString("item_poster").takeIf { it.isNotBlank() }, o.optLong("at"),
    )

    /** Mensagens com [friendId] mais novas que [after] (0 = as últimas 100). Abrir a conversa marca as dele como lidas. */
    suspend fun dmThread(token: String, friendId: String, after: Long): DmThread {
        val raw = rpc("dm_thread", JSONObject().put("p_friend", friendId).put("p_after", after), token, MISSING_CHAT)
        val o = runCatching { JSONObject(raw) }.getOrElse { throw AuthError("Resposta inesperada do servidor. Tente de novo.") }
        val a = o.optJSONArray("msgs") ?: JSONArray()
        val lv = o.optJSONArray("live")?.let { l -> (0 until l.length()).map { l.optLong(it) } }
        return DmThread(o.optString("status"), (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::dmMsg) }, lv, o.optLong("upto"), o.optLong("read_upto"))
    }

    /** Avisa que eu vi as mensagens deste amigo (vira "Visualizado" para ele). Falha em silêncio (ex.: SQL amigos10 não rodado). */
    suspend fun dmMarkRead(token: String, friendId: String) {
        runCatching { rpc("dm_mark_read", JSONObject().put("p_friend", friendId), token) }
    }

    private const val MISSING_DEL = "Apagar mensagens ainda não foi ligado no Supabase: rode o SQL amigos6.sql (passo 4n do CONFIGURAR_CONTA.md)."

    /** Apaga uma mensagem. [all] = para os dois (só vale nas mensagens que eu mandei); senão some só para mim. */
    suspend fun dmDelete(token: String, id: Long, all: Boolean) {
        rpc("dm_delete", JSONObject().put("p_id", id).put("p_all", all), token, MISSING_DEL)
    }

    /** Apaga a conversa inteira só para mim (o amigo continua com as mensagens dele). */
    suspend fun dmClear(token: String, friendId: String) {
        rpc("dm_clear", JSONObject().put("p_friend", friendId), token, MISSING_DEL)
    }

    /** Minhas conversas, a mais recente primeiro. */
    suspend fun dmInbox(token: String): List<Convo> {
        val raw = rpc("dm_inbox", JSONObject(), token, MISSING_CHAT)
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { n ->
            val o = a.optJSONObject(n) ?: return@mapNotNull null
            val last = if (o.optString("last_kind") == "share") "Recomendou “${o.optString("last_title")}”" else o.optString("last_body")
            Convo(person(o), last, o.optLong("at"), o.optBoolean("last_mine"), o.optInt("unread"), o.optString("last_body"), o.optString("last_kind", "text"))
        }
    }
}
