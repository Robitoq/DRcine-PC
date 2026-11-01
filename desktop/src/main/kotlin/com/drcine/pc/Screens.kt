package com.drcine.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drcine.app.Api
import com.drcine.app.CastMember
import com.drcine.app.Item
import com.drcine.app.ShelfRow
import com.drcine.app.Store
import com.drcine.app.jsonStrings
import com.drcine.app.s
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ------------------------------------------------------------------------------------------------
//  Início / Filmes / Séries
// ------------------------------------------------------------------------------------------------
@Composable
fun ShelvesScreen(type: String?, addonsVersion: Int, onOpen: (Item) -> Unit) {
    var rows by remember(type, addonsVersion) { mutableStateOf<List<ShelfRow>?>(null) }
    var hist by remember(type, addonsVersion) { mutableStateOf<List<Item>>(emptyList()) }

    LaunchedEffect(type, addonsVersion) {
        rows = null
        hist = Store.list("hist").filter { type == null || it.type == type }
        rows = withContext(Dispatchers.IO) {
            val ads = Store.addons()
            runCatching { Api.shelves(ads, type, 12) }.getOrDefault(emptyList())
        }
    }

    val r = rows
    when {
        r == null -> Loading("Carregando o catálogo…")
        r.isEmpty() && hist.isEmpty() -> Centered {
            Text("Nada para mostrar por enquanto.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Confira a internet ou instale um addon na aba Addons.", color = Muted)
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (hist.isNotEmpty()) item { ItemRow("Continuar assistindo", hist, onOpen) }
            for (row in r) item { ItemRow(row.label, row.items, onOpen) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
//  Busca
// ------------------------------------------------------------------------------------------------
@Composable
fun SearchScreen(q: String, onOpen: (Item) -> Unit) {
    var results by remember(q) { mutableStateOf<List<Item>>(emptyList()) }
    var done by remember(q) { mutableStateOf(false) }

    LaunchedEffect(q) {
        done = false
        Store.addRecent(q)
        val all = withContext(Dispatchers.IO) {
            runCatching { Api.search(Store.addons(), q, onPartial = { results = it }) }.getOrDefault(emptyList())
        }
        results = all
        done = true
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Resultados para “$q”", fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
        )
        when {
            results.isEmpty() && !done -> Loading("Buscando…")
            results.isEmpty() -> Centered { Text("Nenhum resultado.", color = Muted) }
            else -> PosterGrid(results, onOpen)
        }
    }
}

@Composable
fun PosterGrid(list: List<Item>, onOpen: (Item) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list) { PosterCard(it, Modifier.fillMaxWidth()) { onOpen(it) } }
    }
}

// ------------------------------------------------------------------------------------------------
//  Minha lista
// ------------------------------------------------------------------------------------------------
@Composable
fun MyListScreen(onOpen: (Item) -> Unit) {
    val list = remember { MyList.items() }
    if (list.isEmpty()) {
        Centered {
            Text("Sua lista está vazia.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Abra um título e toque em “Minha lista”.", color = Muted)
        }
    } else {
        PosterGrid(list, onOpen)
    }
}

// ------------------------------------------------------------------------------------------------
//  Addons
// ------------------------------------------------------------------------------------------------
@Composable
fun AddonsScreen() {
    var list by remember { mutableStateOf(Store.addons()) }
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Addons", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Os addons são as fontes de catálogo e de servidores (protocolo do Stremio). Cole o link do manifest para instalar um novo.",
            color = Muted,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("Link do addon (manifest.json)") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        msg = null
                        try {
                            val a = withContext(Dispatchers.IO) { Api.addon(url.trim()) }
                            withContext(Dispatchers.IO) { Store.saveAddons(Store.addons().filter { it.url != a.url } + a) }
                            list = Store.addons()
                            url = ""
                            msg = "Instalado: ${a.name}"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            msg = "Não deu para instalar: ${e.message ?: "erro desconhecido"}"
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Instalando…" else "Instalar") }
        }
        msg?.let { Text(it, color = Accent, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(20.dp))
        if (list.isEmpty()) Text("Nenhum addon instalado.", color = Muted)
        list.forEach { a ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(CardBg).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.Bold)
                    Text(a.url, color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = {
                    Store.saveAddons(Store.addons().filter { it.url != a.url })
                    list = Store.addons()
                }) { Text("Remover") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
//  Detalhes (filme ou série)
// ------------------------------------------------------------------------------------------------
@Composable
fun DetailsScreen(item: Item, onBack: () -> Unit, onPlay: (Item, String?, String?) -> Unit) {
    var meta by remember(item.id) { mutableStateOf<JSONObject?>(null) }
    var cast by remember(item.id) { mutableStateOf<List<CastMember>>(emptyList()) }
    var loaded by remember(item.id) { mutableStateOf(false) }
    var inList by remember(item.id) { mutableStateOf(MyList.has(item)) }

    LaunchedEffect(item.id, item.type) {
        val m = withContext(Dispatchers.IO) { runCatching { Api.meta(Store.addons(), item) }.getOrNull() }
        meta = m
        loaded = true
        cast = withContext(Dispatchers.IO) { runCatching { Api.cast(m, item) }.getOrDefault(emptyList()) }
    }

    val m = meta
    val title = m?.s("name") ?: item.title
    val desc = m?.s("description") ?: item.overview
    val year = m?.s("releaseInfo") ?: m?.s("year") ?: item.year
    val rating = m?.s("imdbRating") ?: item.rating
    val runtime = m?.s("runtime") ?: ""
    val genres = remember(m) { m?.optJSONArray("genres")?.let { jsonStrings(it) } ?: emptyList() }
    val bg = m?.s("background") ?: item.backdrop ?: item.poster
    val eps = remember(m) { if (item.type == "series") Api.eps(m, item.id) else emptyList() }

    Box(Modifier.fillMaxSize()) {
        RemoteImage(bg, Modifier.fillMaxWidth().height(420.dp), ContentScale.Crop)
        Box(
            Modifier.fillMaxWidth().height(420.dp)
                .background(Brush.verticalGradient(listOf(Color(0x80000000), MaterialTheme.colorScheme.background)))
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp)) {
            TextButton(onClick = onBack) { Text("← Voltar") }
            Spacer(Modifier.height(50.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RemoteImage(item.poster, Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    val line = listOf(year, runtime, if (rating.isNotBlank()) "IMDb $rating" else "")
                        .filter { it.isNotBlank() }.joinToString("   •   ")
                    if (line.isNotEmpty()) Text(line, color = Muted, modifier = Modifier.padding(top = 6.dp))
                    if (genres.isNotEmpty()) Text(genres.joinToString(", "), color = Accent, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(14.dp))
                    if (desc.isNotBlank()) Text(desc, lineHeight = 22.sp)
                    else if (!loaded) Text("Carregando informações…", color = Muted)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (item.type == "series") {
                            val last = Store.lastEp(item.id)
                            val target = eps.firstOrNull { it.id == last } ?: eps.firstOrNull()
                            if (target != null) {
                                val txt = "T${target.season}:E${target.number}"
                                Button(onClick = { onPlay(item, target.id, txt) }) {
                                    Text(if (last != null && target.id == last) "Continuar $txt" else "Assistir $txt")
                                }
                            }
                        } else {
                            Button(onClick = { onPlay(item, null, null) }) { Text("Assistir") }
                        }
                        OutlinedButton(onClick = { inList = MyList.toggle(item) }) {
                            Text(if (inList) "Remover da minha lista" else "Minha lista")
                        }
                    }
                    if (cast.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Text("Elenco", fontWeight = FontWeight.Bold)
                        Text(cast.joinToString(", ") { it.name }, color = Muted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            if (eps.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                Text("Episódios", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                val seasons = remember(eps) { eps.map { it.season }.distinct() }
                var season by remember(eps) {
                    mutableStateOf(
                        Store.lastEp(item.id)?.let { id -> eps.firstOrNull { it.id == id }?.season } ?: seasons.first()
                    )
                }
                val seen = remember(item.id) { Store.seenEps(item.id) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    seasons.forEach { s ->
                        val label = if (s == 0) "Extras" else "Temporada $s"
                        if (s == season) Button(onClick = { season = s }) { Text(label) }
                        else OutlinedButton(onClick = { season = s }) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                eps.filter { it.season == season }.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { onPlay(item, e.id, "T${e.season}:E${e.number}") }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${e.number}", Modifier.width(44.dp), color = Accent, fontWeight = FontWeight.Bold)
                        Text(e.title, Modifier.weight(1f))
                        val pr = Store.progressOf(e.id)
                        when {
                            e.id in seen -> Text("visto", color = Muted)
                            pr != null -> Text("$pr%", color = Muted)
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}
