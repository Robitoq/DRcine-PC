package com.drcine.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Ligação com o servidor de contas (Supabase). Preencha [URL] e [ANON_KEY] depois de criar o projeto
 * (passo a passo em CONFIGURAR_CONTA.md). Enquanto estiverem vazios, as contas ficam DESLIGADAS e o app funciona como sempre.
 * A chave "anon" pode ficar no app de propósito: quem protege os dados são as regras do banco, não o segredo da chave.
 */
object CloudConfig {
    const val URL = "https://krfedplsvxquujvbgbpy.supabase.co"
    const val ANON_KEY = "sb_publishable_pqGUo0I7Wd9lm08RkyDvIg_r0bvwxyp"   // chave pública (publishable) do projeto
    const val CONTACT = "robertsferreira4@gmail.com"   // e-mail de contato mostrado na Política de Privacidade
    val enabled: Boolean get() = URL.startsWith("https://") && ANON_KEY.length > 20
}

class AuthSession(val access: String, val refresh: String, val expiresAt: Long, val id: String, val email: String, val name: String, val avatar: String = "")

/** Erro já com mensagem pronta para mostrar à pessoa. */
class AuthError(msg: String, val code: Int = 0) : Exception(msg)

/** Login por e-mail + senha + código de 6 números. Fala direto com a API do Supabase Auth. */
object AuthApi {
    private val JSON = "application/json".toMediaType()

    private suspend fun call(method: String, path: String, body: JSONObject?, token: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + path)
                .header("apikey", CloudConfig.ANON_KEY)
                .method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
                .apply {
                    // Chave nova ("sb_publishable_…") não é um JWT e não vai no Authorization; a chave antiga "anon" (eyJ…) pode ir.
                    val bearer = token ?: CloudConfig.ANON_KEY.takeIf { !it.startsWith("sb_") }
                    if (bearer != null) header("Authorization", "Bearer $bearer")
                }
                .build()
            try {
                Net.api.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                -1 to (e.message ?: "")
            }
        }

    private fun fail(c: Int, what: String): Nothing = throw AuthError(
        when {
            c == -1 -> "Sem conexão com o servidor. Confira sua internet."
            c == 429 -> "Muitas tentativas. Espere um minuto e tente de novo."
            what == "verify" && c in 400..422 -> "Código inválido ou expirado. Confira o código ou peça um novo."
            what == "send" && c in 400..422 -> "Não aceitei esse e-mail. Confira se digitou certo."
            what == "signup" && c in 400..422 -> "Não consegui criar a conta. Use uma senha com pelo menos 6 caracteres."
            what == "password" && c in 400..422 -> "Não aceitei essa senha. Use pelo menos 6 caracteres e diferente da anterior."
            what == "login" && c in 400..422 -> "E-mail ou senha incorretos."
            else -> "Não deu certo agora (erro $c). Tente de novo em instantes."
        }, c
    )

    private fun nameOf(u: JSONObject) = u.optJSONObject("user_metadata")?.optString("name").orEmpty()
    private fun avatarOf(u: JSONObject) = u.optJSONObject("user_metadata")?.optString("avatar").orEmpty()

    private fun sessionFrom(o: JSONObject): AuthSession {
        val u = o.getJSONObject("user")
        return AuthSession(
            o.getString("access_token"), o.getString("refresh_token"),
            System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L,
            u.getString("id"), u.optString("email"), nameOf(u), avatarOf(u)
        )
    }

    /** Manda o código por e-mail (cria a conta se for a primeira vez). */
    suspend fun sendCode(email: String) {
        val (c, _) = call("POST", "/auth/v1/otp", JSONObject().put("email", email.trim()).put("create_user", true))
        if (c !in 200..299) fail(c, "send")
    }

    /** Manda o código só para quem JÁ tem conta (usado no "Entrar", depois de conferir a senha). */
    suspend fun sendLoginCode(email: String) {
        val (c, _) = call("POST", "/auth/v1/otp", JSONObject().put("email", email.trim()).put("create_user", false))
        if (c !in 200..299) fail(c, "send")
    }

    /** Cadastro com senha. O Supabase manda um e-mail com o código de confirmação. */
    suspend fun signUp(email: String, password: String) {
        val (c, b) = call("POST", "/auth/v1/signup", JSONObject().put("email", email.trim()).put("password", password))
        if (c !in 200..299) fail(c, "signup")
        // E-mail que já tem conta: o Supabase responde "ok", mas sem identidade e sem mandar e-mail.
        val already = runCatching {
            val o = JSONObject(b)
            val u = o.optJSONObject("user") ?: o
            u.optJSONArray("identities")?.length() == 0
        }.getOrDefault(false)
        if (already) throw AuthError("Esse e-mail já tem conta. Volte e toque em Entrar.")
    }

    /**
     * Confere e-mail + senha. Devolve true se está tudo certo e false se a conta existe mas o e-mail ainda não foi
     * confirmado (nesse caso o código de confirmação é reenviado). A sessão desta chamada é descartada: quem libera
     * o acesso é o código digitado depois.
     */
    suspend fun checkPassword(email: String, password: String): Boolean {
        val (c, b) = call("POST", "/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password))
        if (c in 200..299) return true
        if (c in 400..422 && b.contains("email_not_confirmed")) {
            resendSignup(email)
            return false
        }
        fail(c, "login")
    }

    /** "Esqueci minha senha": manda por e-mail um código para redefinir (o modelo "Reset Password" do Supabase). */
    suspend fun sendRecovery(email: String) {
        val (c, _) = call("POST", "/auth/v1/recover", JSONObject().put("email", email.trim()))
        if (c !in 200..299) fail(c, "send")
    }

    /** Troca a senha da conta logada. */
    suspend fun updatePassword(s: AuthSession, password: String) {
        val (c, _) = call("PUT", "/auth/v1/user", JSONObject().put("password", password), s.access)
        if (c !in 200..299) fail(c, "password")
    }

    /** Reenvia o código de confirmação de cadastro. */
    suspend fun resendSignup(email: String) {
        val (c, _) = call("POST", "/auth/v1/resend", JSONObject().put("type", "signup").put("email", email.trim()))
        if (c !in 200..299) fail(c, "send")
    }

    /** Confere o código digitado e devolve a sessão (login feito). [type]: "email" (entrar) ou "signup" (cadastro novo). */
    suspend fun verify(email: String, code: String, type: String = "email"): AuthSession {
        val (c, b) = call("POST", "/auth/v1/verify", JSONObject().put("type", type).put("email", email.trim()).put("token", code.trim()))
        if (c !in 200..299) fail(c, "verify")
        return try { sessionFrom(JSONObject(b)) } catch (e: Exception) { throw AuthError("Resposta inesperada do servidor. Tente de novo.") }
    }

    suspend fun refresh(s: AuthSession): AuthSession {
        val (c, b) = call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh))
        if (c !in 200..299) fail(c, "refresh")
        return try { sessionFrom(JSONObject(b)) } catch (e: Exception) { throw AuthError("Sua sessão expirou. Entre de novo.") }
    }

    suspend fun updateName(s: AuthSession, name: String): AuthSession {
        val (c, b) = call("PUT", "/auth/v1/user", JSONObject().put("data", JSONObject().put("name", name.trim())), s.access)
        if (c !in 200..299) fail(c, "update")
        val saved = runCatching { nameOf(JSONObject(b)) }.getOrDefault(name.trim())
        CommentsApi.refreshMine(s.access, s.id)   // comentários antigos passam a mostrar o nome novo
        return AuthSession(s.access, s.refresh, s.expiresAt, s.id, s.email, saved, s.avatar)
    }

    private fun avatarPath(s: AuthSession) = "/storage/v1/object/avatars/${s.id}.jpg"

    /** Guarda o endereço da foto (ou "" para tirar) no perfil. */
    private suspend fun saveAvatarUrl(s: AuthSession, url: String): AuthSession {
        val (c, _) = call("PUT", "/auth/v1/user", JSONObject().put("data", JSONObject().put("avatar", url)), s.access)
        if (c !in 200..299) fail(c, "update")
        CommentsApi.refreshMine(s.access, s.id)   // comentários antigos passam a mostrar a foto nova
        return AuthSession(s.access, s.refresh, s.expiresAt, s.id, s.email, s.name, url)
    }

    /** Envia a foto (JPEG já reduzida) para o armazenamento e põe o endereço dela no perfil. Veja CONFIGURAR_CONTA.md (passo da foto). */
    suspend fun setAvatar(s: AuthSession, jpeg: ByteArray): AuthSession {
        val (code, body) = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + avatarPath(s))
                .header("apikey", CloudConfig.ANON_KEY).header("Authorization", "Bearer ${s.access}").header("x-upsert", "true")
                .post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()
            try { Net.api.newCall(req).execute().use { it.code to (it.body?.string() ?: "") } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { -1 to (e.message ?: "") }
        }
        if (code == -1) fail(-1, "avatar")
        if (code !in 200..299) {
            val why = runCatching { JSONObject(body).let { it.optString("message").ifBlank { it.optString("error") } } }.getOrDefault("")
            val hint = when {
                code == 404 || why.contains("not found", true) || why.contains("bucket", true) -> "A pasta de fotos ainda não existe no Supabase: rode o SQL do passo 4b do CONFIGURAR_CONTA.md."
                code == 401 || code == 403 || why.contains("row-level", true) || why.contains("policy", true) -> "O Supabase não deixou enviar: rode (ou rode de novo) o SQL do passo 4b do CONFIGURAR_CONTA.md."
                else -> ""
            }
            throw AuthError(("Não consegui enviar a foto (erro $code). $hint ${if (why.isNotBlank()) "[$why]" else ""}").trim(), code)
        }
        val url = CloudConfig.URL.trimEnd('/') + "/storage/v1/object/public/avatars/${s.id}.jpg?v=${System.currentTimeMillis()}"
        return saveAvatarUrl(s, url)
    }

    /** Tira a foto do perfil e apaga o arquivo. */
    suspend fun removeAvatar(s: AuthSession): AuthSession {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(CloudConfig.URL.trimEnd('/') + avatarPath(s))
                .header("apikey", CloudConfig.ANON_KEY).header("Authorization", "Bearer ${s.access}").delete().build()
            runCatching { Net.api.newCall(req).execute().close() }
        }
        return saveAvatarUrl(s, "")
    }

    /** Apaga a conta e os dados dela (função delete_my_account criada no banco; veja CONFIGURAR_CONTA.md). */
    suspend fun deleteAccount(s: AuthSession) {
        val (c, _) = call("POST", "/rest/v1/rpc/delete_my_account", JSONObject(), s.access)
        if (c !in 200..299) fail(c, "delete")
    }

    /** Encerra a sessão no servidor (se falhar, tudo bem: o app esquece o login do mesmo jeito). */
    suspend fun signOut(s: AuthSession) { runCatching { call("POST", "/auth/v1/logout", JSONObject(), s.access) } }
}
