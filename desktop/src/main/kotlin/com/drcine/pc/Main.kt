package com.drcine.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
        // key(): cada episódio é uma tela de player nova (salva o ponto do anterior e abre o seguinte do zero).
        key(p.epId ?: p.item.id) {
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
                onPlayEpisode = { id, label -> play = PlayReq(p.item, id, label) },
            )
        }
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
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { Logo(26.sp) }
        // Menu em pílula no centro
        Row(
            Modifier.clip(pill).background(CardBg).border(1.dp, Line, pill).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Section.values().forEach { s ->
                val sel = s == section
                Text(
                    s.label,
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = if (sel) Color.Black else Color(0xFFCFD3DC),
                    modifier = Modifier.clip(pill)
                        .background(if (sel) Accent else Color.Transparent)
                        .clickable { onSection(s) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        // Busca à direita (Enter para buscar)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.width(280.dp).clip(pill).background(CardBg).border(1.dp, Line, pill)
                    .padding(horizontal = 18.dp, vertical = 11.dp)
                    .onPreviewKeyEvent {
                        if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { onSearch(); true } else false
                    },
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Buscar filmes e séries  (Enter)", color = Muted, fontSize = 14.sp)
                        inner()
                    }
                },
            )
        }
    }
}
