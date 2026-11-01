package com.drcine.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drcine.app.Api
import com.drcine.app.Item
import com.drcine.app.Net
import com.drcine.app.Store
import kotlinx.coroutines.delay
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.event.KeyAdapter
import java.awt.event.MouseAdapter

/**
 * Envolve o VLC. Os eventos do VLC chegam em outra thread e NÃO podem chamar o player de volta,
 * então aqui eles só levantam bandeiras; quem lê as bandeiras é o laço da tela (a cada 0,5 s).
 */
class VlcCtl {
    val available: Boolean = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    val component: EmbeddedMediaPlayerComponent? =
        if (available) runCatching { EmbeddedMediaPlayerComponent() }.getOrNull() else null

    @Volatile var errorFlag = false
    @Volatile var endedFlag = false
    @Volatile var playingFlag = false
    @Volatile var onKey: ((Int) -> Unit)? = null
    @Volatile var onDoubleClick: (() -> Unit)? = null

    private val mp get() = component?.mediaPlayer()

    init {
        mp?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) { errorFlag = true }
            override fun finished(mediaPlayer: MediaPlayer) { endedFlag = true }
            override fun playing(mediaPlayer: MediaPlayer) { playingFlag = true }
        })
        // O quadro do vídeo é um componente "pesado" do Java e fica com o teclado e o mouse: repassa para a tela.
        component?.components?.filterIsInstance<java.awt.Canvas>()?.firstOrNull()?.let { v ->
            v.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) { onKey?.invoke(e.keyCode) }
            })
            v.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount >= 2) onDoubleClick?.invoke() else toggle()
                    v.requestFocusInWindow()
                }
            })
        }
    }

    fun play(url: String, headers: Map<String, String>?, startMs: Long) {
        val p = mp ?: return
        errorFlag = false; endedFlag = false; playingFlag = false
        val opts = ArrayList<String>()
        val ua = headers?.entries?.firstOrNull { it.key.equals("user-agent", true) }?.value ?: Net.UA
        opts += ":http-user-agent=$ua"
        val ref = headers?.entries?.firstOrNull { it.key.equals("referer", true) || it.key.equals("referrer", true) }?.value
        if (ref != null) opts += ":http-referrer=$ref"
        opts += ":network-caching=2500"
        if (startMs > 0) opts += ":start-time=${startMs / 1000.0}"
        p.media().play(url, *opts.toTypedArray())
    }

    fun toggle() { mp?.controls()?.pause() }
    fun seekBy(ms: Long) { mp?.controls()?.skipTime(ms) }
    fun seekTo(ms: Long) { mp?.controls()?.setTime(ms) }
    fun setVolume(v: Int) { mp?.audio()?.setVolume(v) }
    fun timeMs(): Long = mp?.status()?.time() ?: 0L
    fun lengthMs(): Long = mp?.status()?.length() ?: 0L
    fun isPlaying(): Boolean = mp?.status()?.isPlaying ?: false

    fun shutdown() {
        onKey = null
        onDoubleClick = null
        val c = component ?: return
        // release() pode demorar um pouco: faz em outra thread para a tela não travar.
        Thread { runCatching { c.mediaPlayer().controls().stop() }; runCatching { c.release() } }.start()
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

@Composable
fun PlayerScreen(
    item: Item,
    epId: String?,
    epLabel: String?,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val ctl = remember { VlcCtl() }
    if (ctl.component == null) {
        VlcMissing(onBack)
        return
    }

    val sid = epId ?: item.id
    val title = if (epLabel != null) "${item.title}  ·  $epLabel" else item.title

    val session = remember(sid) { Api.streamSession(Store.addons(), item.type, sid) }
    val streams by session.live.collectAsState()
    val searching = !session.finished.collectAsState().value
    val playable = remember(streams) { streams.filter { it.second.url != null } }

    var current by remember(sid) { mutableStateOf<Pair<String, com.drcine.app.Stream>?>(null) }
    var failed by remember(sid) { mutableStateOf(setOf<String>()) }
    var openedAt by remember(sid) { mutableStateOf(0L) }
    var status by remember(sid) { mutableStateOf("Procurando servidores…") }
    var pos by remember(sid) { mutableStateOf(0L) }
    var dur by remember(sid) { mutableStateOf(0L) }
    var playing by remember(sid) { mutableStateOf(false) }
    var drag by remember(sid) { mutableStateOf<Float?>(null) }
    var volume by remember { mutableStateOf(100f) }
    var showServers by remember { mutableStateOf(false) }
    val track = remember(sid) { longArrayOf(0L, 0L) }   // [posição, duração] para salvar ao sair
    val firstOpen = remember(sid) { booleanArrayOf(true) }

    val fsNow by rememberUpdatedState(fullscreen)
    val onFs by rememberUpdatedState(onFullscreen)
    val goBack by rememberUpdatedState(onBack)

    // Atalhos de teclado e duplo clique no vídeo.
    DisposableEffect(ctl) {
        ctl.onKey = { code ->
            when (code) {
                java.awt.event.KeyEvent.VK_SPACE -> ctl.toggle()
                java.awt.event.KeyEvent.VK_LEFT -> ctl.seekBy(-10_000)
                java.awt.event.KeyEvent.VK_RIGHT -> ctl.seekBy(10_000)
                java.awt.event.KeyEvent.VK_F -> onFs(!fsNow)
                java.awt.event.KeyEvent.VK_ESCAPE -> if (fsNow) onFs(false) else goBack()
            }
        }
        ctl.onDoubleClick = { onFs(!fsNow) }
        onDispose {
            Store.savePos(sid, track[0], track[1])
            ctl.shutdown()
        }
    }

    // Escolhe o servidor: o que a pessoa já usava; senão o primeiro que chegou (e não falhou).
    LaunchedEffect(playable.size, failed.size) {
        if (current == null) {
            val pref = Api.preferred(playable, Store.server(item.id))
            val pick = playable.getOrNull(pref)?.takeIf { it.second.url !in failed }
                ?: playable.firstOrNull { it.second.url !in failed }
            if (pick != null) current = pick
        }
    }

    // Toca sempre que o servidor muda.
    LaunchedEffect(current) {
        val c = current ?: return@LaunchedEffect
        val st = c.second
        val url = st.url ?: return@LaunchedEffect
        status = "Abrindo ${c.first}…"
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val h = st.headers.takeIf { it.isNotEmpty() } ?: Net.headersFor(host, url)
        val resume = if (firstOpen[0]) Store.resumePos(sid) else track[0]
        firstOpen[0] = false
        openedAt = System.currentTimeMillis()
        ctl.play(url, h, resume)
        Store.saveServer(item.id, c.first, st.label)
        Store.put("hist", item)
        if (epId != null) Store.setLastEp(item.id, epId)
    }

    // Laço de acompanhamento: tempo, bandeiras do VLC, servidor que falhou, salvar posição.
    LaunchedEffect(ctl) {
        var lastSave = 0L
        var marked = false
        while (true) {
            delay(500)
            pos = ctl.timeMs(); dur = ctl.lengthMs(); playing = ctl.isPlaying()
            if (pos > 0) track[0] = pos
            if (dur > 0) track[1] = dur
            val now = System.currentTimeMillis()
            val cur = current
            if (ctl.playingFlag && status.isNotEmpty()) status = ""
            val stalled = cur != null && !ctl.playingFlag && now - openedAt > 20_000
            if (cur != null && (ctl.errorFlag || stalled)) {
                ctl.errorFlag = false
                Net.markBad(cur.second.url)
                failed = failed + (cur.second.url ?: "")
                current = null
                status = "Esse servidor falhou, tentando outro…"
            }
            if (ctl.endedFlag) {
                ctl.endedFlag = false
                Store.savePos(sid, track[1], track[1])   // terminou: apaga o ponto salvo
                if (epId != null) Store.markSeen(item.id, epId)
                track[0] = 0L
                status = "Fim."
            }
            if (playing && now - lastSave > 5_000) {
                Store.savePos(sid, pos, dur)
                lastSave = now
            }
            if (!marked && epId != null && dur > 0 && pos > dur * 0.9) {
                Store.markSeen(item.id, epId)
                marked = true
            }
        }
    }

    LaunchedEffect(volume) { ctl.setVolume(volume.toInt()) }

    val noServer = !searching && playable.isEmpty()
    val allFailed = current == null && playable.isNotEmpty() && playable.all { it.second.url in failed } && !searching

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { goBack() }) { Text("← Voltar") }
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                val msg = when {
                    noServer -> "Nenhum servidor encontrado."
                    allFailed -> "Nenhum servidor funcionou."
                    status.isNotEmpty() -> status
                    else -> ""
                }
                if (msg.isNotEmpty()) Text(msg, color = Accent, fontSize = 13.sp)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SwingPanel(
                factory = { ctl.component!! },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            if (showServers) {
                LazyColumn(Modifier.width(340.dp).fillMaxHeight().background(Color(0xFF111113)).padding(8.dp)) {
                    item { Text("Servidores", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                    itemsIndexed(playable) { _, p ->
                        val sel = p.second.url == current?.second?.url
                        val lbl = p.second.label.replace('\n', ' ').take(70)
                        TextButton(
                            onClick = { failed = failed - (p.second.url ?: ""); current = p },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${p.first} — $lbl", maxLines = 2, fontSize = 13.sp,
                                color = if (sel) Accent else Color.White,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF111113)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { ctl.toggle() }) { Text(if (playing) "Pausar" else "Tocar") }
            OutlinedButton(onClick = { ctl.seekBy(-10_000) }) { Text("-10s") }
            OutlinedButton(onClick = { ctl.seekBy(10_000) }) { Text("+10s") }
            Text(fmt(pos), fontSize = 13.sp)
            Slider(
                value = drag ?: (if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { ctl.seekTo((it * dur).toLong()) }
                    drag = null
                },
                modifier = Modifier.weight(1f),
            )
            Text(fmt(dur), fontSize = 13.sp)
            Text("Vol", fontSize = 13.sp)
            Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..120f, modifier = Modifier.width(110.dp))
            OutlinedButton(onClick = { showServers = !showServers }) { Text("Servidores") }
            OutlinedButton(onClick = { onFullscreen(!fullscreen) }) { Text(if (fullscreen) "Sair da tela cheia" else "Tela cheia") }
        }
    }
}

@Composable
private fun VlcMissing(onBack: () -> Unit) {
    Centered {
        Text("O DRcine usa o VLC para tocar os vídeos.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Não encontrei o VLC neste computador. Instale a versão de 64 bits (videolan.org) e abra o DRcine de novo.",
            color = Muted,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://www.videolan.org/vlc/")) }
            }) { Text("Abrir site do VLC") }
            OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
    }
}
