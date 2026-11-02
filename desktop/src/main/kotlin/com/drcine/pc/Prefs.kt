package com.drcine.pc

import com.drcine.app.AddonStreamCache
import com.drcine.app.Ep
import com.drcine.app.Item
import com.drcine.app.Prefs
import com.drcine.app.Store
import com.drcine.app.StreamCache
import com.drcine.app.StreamCacheStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Addons que já vêm instalados na primeira abertura (os mesmos do app do celular, menos o de legendas). */
val DEFAULT_ADDONS = listOf(
    "FenixFlix" to "https://fenixflix.fenixhub.online/manifest.json",
    "SuperStream" to "https://da5f663b4690-superstream.baby-beamup.club/manifest.json",
    "BSCine" to "https://bscine.alwaysdata.net/manifest.json",
    "FrostStream" to "https://froststream.cloutteam.com/manifest.json",
)

/** O equivalente PC das SharedPreferences do Android: tudo num arquivo JSON dentro da pasta do usuário. */
class JsonPrefs(private val file: File) : Prefs {
    private val o: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
    @Volatile private var dirty = false

    init {
        // Grava em segundo plano (no máximo 1x por segundo) e também ao fechar o programa.
        Thread {
            while (true) {
                Thread.sleep(1000)
                flushIfDirty()
            }
        }.apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { flushIfDirty() })
    }

    @Synchronized private fun flushIfDirty() {
        if (!dirty) return
        dirty = false
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(o.toString())
        }
    }

    @Synchronized override fun getString(k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
    @Synchronized override fun putString(k: String, v: String) { o.put(k, v); dirty = true }
    @Synchronized override fun getLong(k: String, d: Long): Long = o.optLong(k, d)
    @Synchronized override fun putLong(k: String, v: Long) { o.put(k, v); dirty = true }
    @Synchronized override fun remove(k: String) { o.remove(k); dirty = true }
    @Synchronized override fun keys(): Set<String> = HashSet(o.keySet())
}

/** Cache dos servidores achados pelos addons (fica só na memória; some ao fechar). */
class MemStreamCache : StreamCacheStore {
    private val m = ConcurrentHashMap<String, AddonStreamCache>()
    override fun get(imdbId: String, addonUrl: String): AddonStreamCache? = m["$addonUrl|$imdbId"]
    override fun put(entry: AddonStreamCache) {
        if (m.size > 400) m.clear()
        m["${entry.addonUrl}|${entry.imdbId}"] = entry
    }
    override fun deleteOlderThan(minTimestamp: Long) { m.entries.removeIf { it.value.timestamp < minTimestamp } }
    override fun clear() { m.clear() }
}

/** Episódios da série que está aberta (a tela de detalhes guarda aqui; o player usa para "próximo episódio"). */
object EpCache {
    val m = ConcurrentHashMap<String, List<Ep>>()
}

/** "Minha lista" (sem o limite de 20 itens do histórico). */
object MyList {
    private const val KEY = "mylist_pc"
    lateinit var prefs: Prefs

    fun items(): List<Item> = runCatching {
        val a = JSONArray(prefs.getString(KEY) ?: "[]")
        List(a.length()) {
            val o = a.getJSONObject(it)
            Item(
                o.getString("id"), o.getString("type"), o.getString("title"),
                o.optString("poster").takeIf { s -> s.isNotEmpty() },
                o.optString("backdrop").takeIf { s -> s.isNotEmpty() },
                "", "", "",
            )
        }
    }.getOrDefault(emptyList())

    fun has(i: Item): Boolean = items().any { it.id == i.id && it.type == i.type }

    private fun save(l: List<Item>) {
        val a = JSONArray()
        l.forEach {
            a.put(
                JSONObject().put("id", it.id).put("type", it.type).put("title", it.title)
                    .put("poster", it.poster ?: "").put("backdrop", it.backdrop ?: "")
            )
        }
        prefs.putString(KEY, a.toString())
    }

    /** Adiciona ou remove. Devolve true se o título ficou na lista. */
    fun toggle(i: Item): Boolean {
        val cur = items()
        return if (has(i)) {
            save(cur.filter { !(it.id == i.id && it.type == i.type) })
            false
        } else {
            save(listOf(i) + cur)
            true
        }
    }
}

object Boot {
    fun init() {
        val dir = File(System.getProperty("user.home"), ".drcine-pc")
        val prefs = JsonPrefs(File(dir, "dados.json"))
        Store.init(prefs)
        MyList.prefs = prefs
        StreamCache.init(MemStreamCache())
    }
}
