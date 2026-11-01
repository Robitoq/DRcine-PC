package com.drcine.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Uma mensagem da conversa de um aviso: [owner] = true quando foi o dono que escreveu. */
data class ThreadMsg(val owner: Boolean, val text: String, val at: Long)

/** Um aviso de problema enviado por alguém pelo botão flutuante, com a conversa (respostas) dele. */
data class CloudReport(val id: Long, val name: String, val email: String, val text: String, val app: String, val info: String, val at: Long, val msgs: List<ThreadMsg> = emptyList(), val chatOpen: Boolean = false)

/** Um aviso visto por quem enviou (aba da bolinha): o texto dele + as respostas. */
data class MyReport(val id: Long, val text: String, val at: Long, val msgs: List<ThreadMsg>, val chatOpen: Boolean = false)

/** Alguém que está online agora (nome vazio = sem conta). */
data class OnlineUser(val name: String, val email: String, val watching: String, val at: Long, val model: String = "", val android: String = "", val app: String = "", val first: Long = 0L)

/** Um aparelho que já abriu o app: modelo, versões, 1ª abertura e último acesso (vem do SQL 4h). */
data class DeviceRow(val name: String, val email: String, val model: String, val android: String, val app: String, val watching: String, val first: Long, val last: Long)

/** Uma conta criada. */
data class AccountRow(val name: String, val email: String, val at: Long)

/** Uma conta com a última vez que foi vista (0 = nunca abriu o app depois de criar a conta). Vem do SQL 4g. */
data class UserAct(val name: String, val email: String, val created: Long, val last: Long, val watching: String, val devices: Int)

/** Números do painel do dono. */
data class AdminStats(val devices: Int, val online: Int, val watching: Int, val accounts: Int, val newToday: Int, val peak: Int)

/**
 * Painel do dono + avisos de erro + "quem está online" (tabelas reports/presence no Supabase; SQL no passo 4d do CONFIGURAR_CONTA.md).
 * Só o dono (e-mail em [CloudConfig.CONTACT]) consegue LER os avisos e os números: quem protege isso é o banco, não o app.
 */
object AdminApi {
    private val JSON = "application/json".toMediaType()
    private fun url(p: String) = CloudConfig.URL.trimEnd('/') + "/rest/v1/" + p

    private suspend fun call(method: String, u: String, body: String?, token: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(u).header("apikey", CloudConfig.ANON_KEY).header("Accept", "application/json")
            val bearer = token ?: CloudConfig.ANON_KEY.takeIf { !it.startsWith("sb_") }
            if (bearer != null) b.header("Authorization", "Bearer $bearer")
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
                c == 404 || body.contains("PGRST202") || body.contains("PGRST205") || body.contains("does not exist") ->
                    "O painel ainda não foi ligado no Supabase: rode o SQL do passo 4d do CONFIGURAR_CONTA.md."
                c == 401 || c == 403 -> "Só o dono do app (com a conta do e-mail de contato) vê isso. Entre de novo na sua conta."
                msg.startsWith("Espere") || msg.startsWith("O dono") -> msg
                else -> "Não deu certo agora (erro $c). Tente de novo em instantes."
            }, c
        )
    }

    /** "Estou aqui": chamado a cada ~45 s. Falha em silêncio (nunca atrapalha o app). */
    suspend fun ping(device: String, watching: String, token: String? = null, model: String = "", android: String = "", app: String = "") {
        runCatching {
            val b = JSONObject().put("p_device", device).put("p_watching", watching.take(80))
                .put("p_model", model.take(60)).put("p_android", android.take(20)).put("p_app", app.take(20))
            val (c, _) = call("POST", url("rpc/ping2"), b.toString(), token)
            // SQL 4h ainda não rodado: usa o "estou online" antigo (sem modelo), como antes.
            if (c == 404 || c == 400) call("POST", url("rpc/ping"), JSONObject().put("p_device", device).put("p_watching", watching.take(80)).toString(), token)
        }
    }

    /** Envia um aviso de problema. Se a pessoa estiver logada, nome e e-mail vão junto (o banco preenche). */
    suspend fun sendReport(token: String?, device: String, text: String, app: String, info: String) {
        val b = JSONObject().put("p_device", device).put("p_body", text.trim().take(1000)).put("p_app", app.take(40)).put("p_info", info.take(200))
        val (c, r) = call("POST", url("rpc/send_report"), b.toString(), token)
        if (c !in 200..299) fail(c, r)
    }

    suspend fun stats(token: String): AdminStats {
        val (c, r) = call("POST", url("rpc/admin_stats"), "{}", token)
        if (c !in 200..299) fail(c, r)
        val o = JSONObject(r)
        return AdminStats(o.optInt("devices"), o.optInt("online"), o.optInt("watching"), o.optInt("accounts"), o.optInt("new_today"), o.optInt("peak"))
    }

    private fun msgs(a: JSONArray?, ownerKey: String, textKey: String): List<ThreadMsg> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
            .map { ThreadMsg(it.optBoolean(ownerKey), it.optString(textKey), it.optLong("at")) }.sortedBy { it.at }

    suspend fun reports(token: String): List<CloudReport> {
        val (c, r) = call("GET", url("reports?select=id,name,email,body,app,info,at,chat_open,report_msgs(from_owner,body,at)&order=at.desc&limit=100"), null, token)
        if (c !in 200..299) fail(c, r)
        return runCatching {
            JSONArray(r).let { a ->
                (0 until a.length()).map {
                    a.getJSONObject(it).let { o ->
                        CloudReport(o.optLong("id"), o.optString("name"), o.optString("email"), o.optString("body"), o.optString("app"), o.optString("info"), o.optLong("at"),
                            msgs(o.optJSONArray("report_msgs"), "from_owner", "body"), o.optBoolean("chat_open"))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** O dono responde um aviso. */
    suspend fun ownerReply(token: String, reportId: Long, text: String) {
        val (c, r) = call("POST", url("rpc/owner_reply"), JSONObject().put("p_report", reportId).put("p_body", text.trim().take(1000)).toString(), token)
        if (c !in 200..299) fail(c, r)
    }

    /** O dono aceita (true) ou encerra (false) a conversa de um aviso. Sem aceitar, a pessoa só lê a resposta. */
    suspend fun setChat(token: String, reportId: Long, open: Boolean) {
        val (c, r) = call("POST", url("rpc/owner_set_chat"), JSONObject().put("p_report", reportId).put("p_open", open).toString(), token)
        if (c !in 200..299) fail(c, r)
    }

    /** Quem mandou o aviso vê os dele e as respostas (vale por celular e, se estiver logado, por conta). */
    suspend fun myReports(token: String?, device: String): List<MyReport> {
        val (c, r) = call("POST", url("rpc/my_reports"), JSONObject().put("p_device", device).toString(), token)
        if (c !in 200..299) fail(c, r)
        return runCatching {
            JSONArray(r).let { a ->
                (0 until a.length()).map {
                    a.getJSONObject(it).let { o -> MyReport(o.optLong("id"), o.optString("body"), o.optLong("at"), msgs(o.optJSONArray("msgs"), "o", "b"), o.optBoolean("chat")) }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Quem mandou o aviso responde de volta. */
    suspend fun userReply(token: String?, device: String, reportId: Long, text: String) {
        val b = JSONObject().put("p_device", device).put("p_report", reportId).put("p_body", text.trim().take(1000))
        val (c, r) = call("POST", url("rpc/user_reply"), b.toString(), token)
        if (c !in 200..299) fail(c, r)
    }

    /** Quem está online agora, com nome e e-mail (só o dono). */
    suspend fun online(token: String): List<OnlineUser> {
        val (c, r) = call("POST", url("rpc/admin_online"), "{}", token)
        if (c !in 200..299) fail(c, r)
        return runCatching {
            JSONArray(r).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> OnlineUser(o.optString("name"), o.optString("email"), o.optString("watching"), o.optLong("at"), o.optString("model"), o.optString("android"), o.optString("app"), o.optLong("first")) } } }
        }.getOrDefault(emptyList())
    }

    /** Todas as contas criadas, com nome e e-mail (só o dono). */
    suspend fun accounts(token: String): List<AccountRow> {
        val (c, r) = call("POST", url("rpc/admin_accounts"), "{}", token)
        if (c !in 200..299) fail(c, r)
        return runCatching {
            JSONArray(r).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> AccountRow(o.optString("name"), o.optString("email"), o.optLong("at")) } } }
        }.getOrDefault(emptyList())
    }

    /** Todas as contas com a última vez vista (só o dono; precisa do SQL 4g). Lança AuthError(code 404) se o SQL ainda não foi rodado. */
    suspend fun users(token: String): List<UserAct> {
        val (c, r) = call("POST", url("rpc/admin_users"), "{}", token)
        if (c !in 200..299) fail(c, r)
        return runCatching {
            JSONArray(r).let { a ->
                (0 until a.length()).map {
                    a.getJSONObject(it).let { o -> UserAct(o.optString("name"), o.optString("email"), o.optLong("created"), o.optLong("last"), o.optString("watching"), o.optInt("devices")) }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Todos os aparelhos que já abriram o app, com modelo e horas (só o dono; precisa do SQL 4h). Lança AuthError(code 404) se ainda não foi rodado. */
    suspend fun devices(token: String): List<DeviceRow> {
        val (c, r) = call("POST", url("rpc/admin_devices"), "{}", token)
        if (c !in 200..299) fail(c, r)
        return runCatching {
            JSONArray(r).let { a ->
                (0 until a.length()).map {
                    a.getJSONObject(it).let { o -> DeviceRow(o.optString("name"), o.optString("email"), o.optString("model"), o.optString("android"), o.optString("app"), o.optString("watching"), o.optLong("first"), o.optLong("last")) }
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun deleteReport(token: String, id: Long) {
        val (c, r) = call("DELETE", url("reports?id=eq.$id"), null, token)
        if (c !in 200..299) fail(c, r)
    }
}
