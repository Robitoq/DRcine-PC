package com.drcine.app

/**
 * Uma linha do cache de servidores: o que UM addon respondeu para UM título/episódio.
 *  - imdbId: id do filme ou do episódio (ex.: "tt1234567" ou "tt1234567:1:3")
 *  - addonUrl: URL base do addon que gerou os links
 *  - streamsJson: JSON completo devolvido pelo addon, do jeito que veio
 *  - timestamp: momento (ms) em que a busca foi feita
 */
data class AddonStreamCache(val imdbId: String, val addonUrl: String, val streamsJson: String, val timestamp: Long)

/** Armazenamento do cache. No Android é um banco SQLite local ([AndroidStreamCache]); o `shared` só conhece esta interface. */
interface StreamCacheStore {
    fun get(imdbId: String, addonUrl: String): AddonStreamCache?
    fun put(entry: AddonStreamCache)
    fun deleteOlderThan(minTimestamp: Long)
    fun clear()
}

/**
 * Regra do cache: resultado de um addon vale por 2 horas.
 *  - Dentro de 2 h: os links vêm do banco local, sem NENHUMA requisição ao addon.
 *  - Sem cache ou vencido: busca na internet e grava o resultado novo.
 * Sem [init] (ex.: app de PC) o cache fica desligado e o comportamento é o de sempre.
 * Qualquer erro do banco é engolido: cache nunca pode derrubar a busca.
 */
object StreamCache {
    const val TTL_MS = 2 * 60 * 60 * 1000L
    private const val MAX_JSON = 1_500_000   // linhas gigantes estouram o CursorWindow do SQLite (2 MB): nem grava

    @Volatile private var store: StreamCacheStore? = null

    fun init(s: StreamCacheStore) {
        store = s
        // Limpeza dos vencidos em segundo plano (abrir o banco na thread principal travaria a abertura do app).
        Thread { runCatching { s.deleteOlderThan(System.currentTimeMillis() - TTL_MS) } }.apply { isDaemon = true }.start()
    }

    /** JSON guardado, se existir E tiver menos de 2 horas. Relógio mexido (idade negativa) também invalida. */
    fun fresh(imdbId: String, addonUrl: String): String? = runCatching {
        val e = store?.get(imdbId, addonUrl) ?: return null
        val age = System.currentTimeMillis() - e.timestamp
        if (age in 0 until TTL_MS) e.streamsJson else null
    }.getOrNull()

    /** Apaga todo o cache de servidores (Ajustes > Dados). */
    fun clearAll() { runCatching { store?.clear() } }

    fun save(imdbId: String, addonUrl: String, json: String) {
        if (json.length > MAX_JSON) return
        runCatching { store?.put(AddonStreamCache(imdbId, addonUrl, json, System.currentTimeMillis())) }
    }
}
