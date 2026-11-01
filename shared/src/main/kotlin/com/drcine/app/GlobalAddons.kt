package com.drcine.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Addons globais: o DONO instala em Ajustes > Addons e a lista vai para o servidor (tabela `global_addons`, SQL em
 * `addons-globais.sql`). Todos os outros celulares copiam essa lista ([sync]) ao abrir o app e de tempos em tempos.
 * Quem não é dono nem vê a tela de Addons. O servidor é quem garante que só o dono escreve (RLS), não o app.
 */
object GlobalAddons {
    // Usa o mesmo projeto Supabase do login (CloudConfig, em Auth.kt): não precisa preencher nada aqui.
    const val SUPABASE_URL = CloudConfig.URL
    const val SUPABASE_ANON = CloudConfig.ANON_KEY

    private val configured get() = !SUPABASE_URL.contains("SEU-PROJETO") && !SUPABASE_ANON.startsWith("COLE")
    private val JSON = "application/json".toMediaType()

    private fun base(u: String) = u.substringBefore("/manifest.json").trimEnd('/')

    private fun builder(path: String, token: String?): Request.Builder {
        val b = Request.Builder().url("$SUPABASE_URL/rest/v1/$path").header("apikey", SUPABASE_ANON)
        return if (token != null) b.header("Authorization", "Bearer $token") else b
    }

    private fun why(code: Int) = when (code) {
        401, 403 -> "sem permissão (confira o e-mail do dono no addons-globais.sql e se você está logado)"
        404 -> "a tabela global_addons não existe (rode o addons-globais.sql no Supabase)"
        else -> "erro $code do servidor"
    }

    /** Lista global (url sem manifest.json, nome) ou null se não deu para ler (sem internet, sem tabela, sem configurar). */
    suspend fun fetch(): List<Pair<String, String>>? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        runCatching {
            Net.plain.newCall(builder("global_addons?select=url,name&order=created_at.asc", null).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val a = JSONArray(r.body!!.string())
                List(a.length()) { i -> val o = a.getJSONObject(i); base(o.getString("url")) to o.optString("name") }
            }
        }.getOrNull()
    }

    private suspend fun send(req: Request): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Net.plain.newCall(req).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException(why(r.code)) }
        }
    }

    /** DONO: coloca estes addons na lista global (adiciona ou atualiza o nome; não apaga os outros). */
    suspend fun publish(token: String?, list: List<Addon>): Result<Unit> {
        if (!configured) return Result.failure(java.io.IOException("falta preencher SUPABASE_URL e SUPABASE_ANON em GlobalAddons.kt"))
        if (token == null) return Result.failure(java.io.IOException("sua sessão expirou, entre de novo"))
        if (list.isEmpty()) return Result.success(Unit)
        val body = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("url", base(it.url)).put("name", it.name)) } }
        val req = builder("global_addons?on_conflict=url", token).header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toString().toRequestBody(JSON)).build()
        return send(req)
    }

    /** DONO: tira um addon da lista global. */
    suspend fun unpublish(token: String?, url: String): Result<Unit> {
        if (!configured) return Result.failure(java.io.IOException("falta preencher SUPABASE_URL e SUPABASE_ANON em GlobalAddons.kt"))
        if (token == null) return Result.failure(java.io.IOException("sua sessão expirou, entre de novo"))
        return send(builder("global_addons?url=eq.${uriEncode(base(url))}", token).delete().build())
    }

    /** DONO: deixa a lista global IGUAL a esta (remove do servidor o que não está aqui). Recusa lista vazia. */
    suspend fun replaceAll(token: String?, list: List<Addon>): Result<Unit> {
        if (list.isEmpty()) return Result.failure(java.io.IOException("este celular não tem addons para publicar"))
        val keep = list.map { base(it.url) }.toSet()
        val current = fetch() ?: return Result.failure(java.io.IOException("não consegui ler a lista do servidor"))
        publish(token, list).onFailure { return Result.failure(it) }
        current.map { it.first }.filter { it !in keep }.forEach { u -> unpublish(token, u).onFailure { return Result.failure(it) } }
        return Result.success(Unit)
    }

    /**
     * Copia a lista do dono para este celular: instala o que falta e remove o que o dono não tem (pessoas comuns não têm
     * como gerenciar addons). Devolve true se o servidor tem uma lista e este celular já a segue.
     * Lista vazia ou erro de rede = não mexe em nada (o app continua com o que já tem).
     */
    suspend fun sync(): Boolean = coroutineScope {
        val g = fetch() ?: return@coroutineScope false
        if (g.isEmpty()) return@coroutineScope false
        val want = g.map { it.first }
        val have = Store.addons()
        val missing = want.filter { u -> have.none { it.url == u } }
        val got = missing.map { u ->
            async { runCatching { withTimeoutOrNull(25_000L) { Api.addon("$u/manifest.json") } }.getOrNull() }
        }.awaitAll().filterNotNull()
        synchronized(Api.infoLock) {
            val now = Store.addons()
            val merged = (now.filter { it.url in want } + got.filter { a -> now.none { it.url == a.url } })
                .sortedBy { a -> want.indexOf(a.url).let { if (it < 0) Int.MAX_VALUE else it } }
            // Se nada do servidor carregou e tudo que o celular tem sairia, mantém como está (nunca deixa o app vazio por falha de rede).
            if (merged.isNotEmpty() && merged.map { it.url } != now.map { it.url }) Store.saveAddons(merged)
        }
        true
    }

    // ---------- Chaves globais do dono (tabela `global_flags`, SQL em config-global.sql) ----------

    /** Valor de uma chave global ("1"/"0"...), ou null se não existe / não deu para ler. */
    suspend fun flagGet(key: String): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        runCatching {
            Net.plain.newCall(builder("global_flags?select=value&key=eq.${uriEncode(key)}", null).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val a = JSONArray(r.body!!.string())
                if (a.length() == 0) null else a.getJSONObject(0).optString("value")
            }
        }.getOrNull()
    }

    /** DONO: grava uma chave global para todos. */
    suspend fun flagSet(token: String?, key: String, value: String): Result<Unit> {
        if (!configured) return Result.failure(java.io.IOException("servidor não configurado"))
        if (token == null) return Result.failure(java.io.IOException("sua sessão expirou, entre de novo"))
        val body = JSONObject().put("key", key).put("value", value)
        val req = builder("global_flags?on_conflict=key", token).header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(JSONArray().put(body).toString().toRequestBody(JSON)).build()
        return withContext(Dispatchers.IO) {
            runCatching {
                Net.plain.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException(when (r.code) {
                        401, 403 -> "sem permissão (entre com a conta de dono)"
                        404 -> "a tabela global_flags não existe (rode o config-global.sql no Supabase)"
                        else -> "erro ${r.code} do servidor"
                    })
                }
            }
        }
    }

    /** Quem não é dono segue o que o dono escolheu para a imagem dos canais (TMDB). Sem servidor/tabela, não muda nada. */
    suspend fun syncFlags() {
        for (k in listOf("tmdb_on", "cinemeta_on")) flagGet(k)?.let { Store.setFlag(k, it == "1") }
    }
}
