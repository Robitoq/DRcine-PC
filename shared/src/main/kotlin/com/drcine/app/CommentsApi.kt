package com.drcine.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Um comentário guardado no Supabase. Nome e foto vêm da conta de quem comentou (o banco preenche, ninguém consegue fingir ser outra pessoa). */
data class CloudComment(val id: Long, val userId: String, val name: String, val avatar: String, val text: String, val at: Long)

/** Comentários compartilhados (tabela public.comments no Supabase; SQL no passo 4c do CONFIGURAR_CONTA.md). */
object CommentsApi {
    private val JSON = "application/json".toMediaType()
    private fun url(q: String = "") = CloudConfig.URL.trimEnd('/') + "/rest/v1/comments" + q
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun call(method: String, u: String, body: String?, token: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(u).header("apikey", CloudConfig.ANON_KEY).header("Accept", "application/json")
            // Chave nova ("sb_publishable_…") não é JWT e não vai no Authorization; só o token da pessoa (ou a chave antiga "anon").
            val bearer = token ?: CloudConfig.ANON_KEY.takeIf { !it.startsWith("sb_") }
            if (bearer != null) b.header("Authorization", "Bearer $bearer")
            if (method == "POST") b.header("Prefer", "return=representation")
            b.method(method, body?.toRequestBody(JSON))
            try {
                Net.api.newCall(b.build()).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                -1 to (e.message ?: "")
            }
        }

    private fun fail(c: Int, body: String): Nothing {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        throw AuthError(
            when {
                c == -1 -> "Sem conexão com o servidor. Confira sua internet."
                c == 404 || body.contains("PGRST205") || body.contains("does not exist") ->
                    "Os comentários ainda não foram ligados no Supabase: rode o SQL do passo 4c do CONFIGURAR_CONTA.md."
                c == 401 || c == 403 -> "Entre na sua conta de novo para comentar."
                c == 429 -> "Muitas tentativas. Espere um minuto."
                c in 400..422 && msg.startsWith("Espere") -> msg   // aviso do banco contra comentário em sequência
                else -> "Não deu certo agora (erro $c). Tente de novo em instantes."
            }, c
        )
    }

    private fun row(o: JSONObject) = CloudComment(
        o.optLong("id"), o.optString("user_id"), o.optString("name"), o.optString("avatar"), o.optString("body"), o.optLong("at")
    )

    /** Os 100 comentários mais novos deste filme/série ([target] = "movie_tt123"). Qualquer pessoa pode ler. */
    suspend fun list(target: String, token: String? = null): List<CloudComment> {
        val (c, b) = call("GET", url("?target=eq.${enc(target)}&select=id,user_id,name,avatar,body,at&order=at.desc&limit=100"), null, token)
        if (c !in 200..299) fail(c, b)
        return runCatching { JSONArray(b).let { a -> (0 until a.length()).map { row(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    }

    /** Publica um comentário com a conta da pessoa. Devolve o comentário já com nome e foto preenchidos pelo banco. */
    suspend fun add(token: String, target: String, text: String): CloudComment {
        val (c, b) = call("POST", url(), JSONObject().put("target", target).put("body", text.trim().take(500)).toString(), token)
        if (c !in 200..299) fail(c, b)
        return runCatching { row(JSONArray(b).getJSONObject(0)) }.getOrElse { throw AuthError("Resposta inesperada do servidor. Tente de novo.") }
    }

    /** Apaga um comentário (o banco só deixa apagar os da própria pessoa). */
    suspend fun delete(token: String, id: Long) {
        val (c, b) = call("DELETE", url("?id=eq.$id"), null, token)
        if (c !in 200..299) fail(c, b)
    }

    /** Depois de trocar nome/foto: manda o banco reler o perfil e atualizar os comentários antigos da pessoa. Falha em silêncio. */
    suspend fun refreshMine(token: String, userId: String) {
        runCatching { call("PATCH", url("?user_id=eq.${enc(userId)}"), JSONObject().put("avatar", "").toString(), token) }
    }
}
