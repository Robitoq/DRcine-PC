package com.drcine.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Títulos dos episódios em português do Brasil.
 *
 * Os addons trazem o nome do episódio em inglês (ex.: "Simpsons Roasting on an Open Fire"). Aqui o app traduz sozinho,
 * uma temporada de cada vez (a que a pessoa está assistindo primeiro), e GUARDA no aparelho: da segunda vez já abre
 * traduzido, sem internet. É tradução automática: nomes oficiais de TV podem ser diferentes.
 */
object EpTitles {
    private fun key(seriesId: String, season: Int) = "eptr1_${seriesId}_$season"

    private fun load(seriesId: String, season: Int): Map<String, String> {
        val raw = Store.epTr(key(seriesId, season)) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k -> o.getString(k) }
        }.getOrDefault(emptyMap())
    }

    /** Aplica o que já está traduzido e salvo (rápido, sem internet). */
    fun applyCached(seriesId: String, eps: List<Ep>): List<Ep> {
        val maps = eps.map { it.season }.distinct().associateWith { s -> load(seriesId, s) }
        return eps.map { e ->
            val t = maps[e.season]?.get(e.id)
            if (t.isNullOrBlank()) e else e.copy(title = t)
        }
    }

    /** Uma chamada ao tradutor. Devolve o texto traduzido, ou nulo se a internet/serviço falhou. */
    private fun call(text: String): String? = runCatching {
        val q = java.net.URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=pt&dt=t&q=$q"
        val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
        Net.plain.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val body = r.body?.string() ?: return@use null
            val seg = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until seg.length()) sb.append(seg.getJSONArray(i).optString(0, ""))
            sb.toString()
        }
    }.getOrNull()

    /** Traduz uma lista de títulos de uma vez (separados por linha). Se o tradutor juntar/separar linhas, traduz um por um. */
    private fun translate(titles: List<String>): List<String>? {
        val clean = titles.map { it.replace('\n', ' ').trim() }
        val joined = call(clean.joinToString("\n")) ?: return null
        val parts = joined.trim().split("\n").map { it.trim() }
        if (parts.size == clean.size) return parts.mapIndexed { i, p -> p.ifEmpty { titles[i] } }
        return clean.mapIndexed { i, t -> call(t)?.trim()?.ifEmpty { null } ?: titles[i] }
    }

    /** Traduz e salva UMA temporada. Devolve false se a internet falhou (tenta de novo da próxima vez). */
    private suspend fun translateSeason(seriesId: String, season: Int, eps: List<Ep>): Boolean {
        val have = load(seriesId, season)
        val todo = eps.filter { it.season == season && it.id !in have }
        if (todo.isEmpty()) return true
        val out = HashMap<String, String>(have)
        for (chunk in todo.chunked(25)) {
            val tr = withContext(Dispatchers.IO) { translate(chunk.map { it.title }) } ?: return false
            chunk.forEachIndexed { i, e -> out[e.id] = tr[i] }
        }
        Store.setEpTr(key(seriesId, season), JSONObject(out as Map<*, *>).toString())
        return true
    }

    /**
     * Traduz todas as temporadas, uma por vez (a [firstSeason] primeiro). A cada temporada pronta chama [onUpdate]
     * com a lista de episódios já traduzida.
     */
    suspend fun translateAll(seriesId: String, eps: List<Ep>, firstSeason: Int, onUpdate: (List<Ep>) -> Unit) {
        val order = eps.map { it.season }.distinct().sortedBy { if (it == firstSeason) -1 else it }
        for (s in order) {
            if (!translateSeason(seriesId, s, eps)) return
            onUpdate(applyCached(seriesId, eps))
            delay(250)
        }
    }
}
