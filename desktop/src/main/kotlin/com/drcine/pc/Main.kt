package com.drcine.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.drcine.app.Api
import com.drcine.app.Item
import com.drcine.app.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() {
    Boot.init()
    application {
        val winState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        val icon = remember { runCatching { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }.getOrNull() }
        Window(onCloseRequest = ::exitApplication, title = "DRcine", state = winState, icon = icon) {
            DrcineTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(winState)
                }
            }
        }
    }
}

enum class Section(val label: String) {
    HOME("Início"), MOVIES("Filmes"), SERIES("Séries"), LIST("Minha lista"), ADDONS("Addons")
}

class PlayReq(val item: Item, val epId: String?, val epLabel: String?)

@Composable
fun App(win: WindowState) {
    var section by remember { mutableStateOf(Section.HOME) }
    var query by remember { mutableStateOf("") }
    var searchQ by remember { mutableStateOf<String?>(null) }
    val detail = remember { mutableStateListOf<Item>() }
    var play by remember { mutableStateOf<PlayReq?>(null) }
    var addonsVersion by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(Store.addons().isNotEmpty()) }

    // Quando a lista de addons muda (instalar/remover), as telas recarregam sozinhas.
    DisposableEffect(Unit) {
        Store.onAddonsChanged = { addonsVersion++ }
        onDispose { Store.onAddonsChanged = null }
    }

    // Primeira abertura: instala os addons padrão. Nas próximas, só atualiza os manifests em segundo plano.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { Api.ensureDefaultAddons(DEFAULT_ADDONS) }
            runCatching { Api.refreshAddons() }
        }
        ready = true
    }

    val openItem: (Item) -> Unit = { detail.add(it) }

    val p = play
    if (p != null) {
        PlayerScreen(
            item = p.item,
            epId = p.epId,
            epLabel = p.epLabel,
            fullscreen = win.placement == WindowPlacement.Fullscreen,
            onFullscreen = { f -> win.placement = if (f) WindowPlacement.Fullscreen else WindowPlacement.Floating },
            onBack = {
                win.placement = WindowPlacement.Floating
                play = null
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                section = section,
                onSection = { section = it; searchQ = null; detail.clear() },
                query = query,
                onQuery = { query = it },
                onSearch = {
                    val q = query.trim()
                    if (q.length >= 2) { searchQ = q; detail.clear() }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val sq = searchQ
                when {
                    !ready -> Loading("Preparando os addons (só na primeira vez)…")
                    detail.isNotEmpty() -> DetailsScreen(
                        item = detail.last(),
                        onBack = { detail.removeAt(detail.lastIndex) },
                        onPlay = { item, ep, label -> play = PlayReq(item, ep, label) },
                    )
                    sq != null -> SearchScreen(sq, openItem)
                    else -> when (section) {
                        Section.HOME -> ShelvesScreen(null, addonsVersion, openItem)
                        Section.MOVIES -> ShelvesScreen("movie", addonsVersion, openItem)
                        Section.SERIES -> ShelvesScreen("series", addonsVersion, openItem)
                        Section.LIST -> MyListScreen(openItem)
                        Section.ADDONS -> AddonsScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun TopBar(
    section: Section,
    onSection: (Section) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF111113)).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(26.sp)
        Spacer(Modifier.width(24.dp))
        Section.values().forEach { s ->
            TextButton(onClick = { onSection(s) }) {
                Text(
                    s.label,
                    color = if (s == section) Accent else Color(0xFFCFCFD6),
                    fontWeight = if (s == section) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Buscar filmes e séries") },
            modifier = Modifier.width(320.dp).onPreviewKeyEvent {
                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { onSearch(); true } else false
            },
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSearch) { Text("Buscar") }
    }
}
