package com.drcine.pc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drcine.app.Api
import com.drcine.app.Item
import com.drcine.app.Net
import com.drcine.app.Store
import kotlinx.coroutines.delay
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/**
 * Envolve o VLC. O vídeo NÃO é mais desenhado numa janela do Windows por baixo do app (era isso que dava tela preta
 * e piscar branco): o VLC entrega cada quadro em memória e o app desenha o quadro como uma imagem. Por isso os
 * controles podem ficar por cima do filme.
 *
 * Os eventos do VLC chegam em outra thread e NÃO podem chamar o player de volta: eles só levantam bandeiras,
 * que a tela lê a cada 0,25 s.
 */
class VlcCtl {
    val available: Boolean = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    private val factory: MediaPlayerFactory? = if (available) runCatching { MediaPlayerFactory() }.getOrNull() else null
    private val player: EmbeddedMediaPlayer? = runCatching { factory?.mediaPlayers()?.newEmbeddedMediaPlayer() }.getOrNull()
    val ok: Boolean get() = player != null

    /** Último quadro do vídeo (a tela lê isto para desenhar). */
    val frame = mutableStateOf<ImageBitmap?>(null)

    @Volatile var errorFlag = false
    @Volatile var endedFlag = false
    @Volatile var playingFlag = false

    private class Fmt(val info: ImageInfo, val bytes: ByteArray, val stride: Int)
    @Volatile private var fmt: Fmt? = null

    init {
        val p = player
        if (p != null) {
            p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun error(mediaPlayer: MediaPlayer) { errorFlag = true }
                override fun finished(mediaPlayer: MediaPlayer) { endedFlag = true }
                override fun playing(mediaPlayer: MediaPlayer) { playingFlag = true }
            })
            p.videoSurface().set(buildSurface())
        }
    }

    // As duas "pontes" abaixo são criadas por Proxy para não depender de quais métodos a interface tem em cada versão do vlcj.
    @Suppress("UNCHECKED_CAST")
    private fun buildSurface(): CallbackVideoSurface {
        val bufferCb = Proxy.newProxyInstance(
            BufferFormatCallback::class.java.classLoader,
            arrayOf<Class<*>>(BufferFormatCallback::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getBufferFormat" -> {
                        val w = args[0] as Int
                        val h = args[1] as Int
                        fmt = Fmt(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), ByteArray(w * h * 4), w * 4)
                        RV32BufferFormat(w, h)
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args[0]
                    "toString" -> "BufferFormatCallbackProxy"
                    else -> null
                }
            },
        ) as BufferFormatCallback

        val renderCb = Proxy.newProxyInstance(
            RenderCallback::class.java.classLoader,
            arrayOf<Class<*>>(RenderCallback::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "display" -> {
                        onFrame((args[1] as Array<ByteBuffer>)[0])
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args[0]
                    "toString" -> "RenderCallbackProxy"
                    else -> null
                }
            },
        ) as RenderCallback

        return CallbackVideoSurface(bufferCb, renderCb, true, VideoSurfaceAdapters.getVideoSurfaceAdapter())
    }

    private fun onFrame(bb: ByteBuffer) {
        val f = fmt ?: return
        bb.rewind()
        bb.get(f.bytes, 0, minOf(f.bytes.size, bb.remaining()))
        val img = org.jetbrains.skia.Image.makeRaster(f.info, f.bytes, f.stride)
        frame.value = img.toComposeImageBitmap()
    }

    fun play(url: String, headers: Map<String, String>?, startMs: Long) {
        val p = player ?: return
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

    fun toggle() { player?.controls()?.pause() }
    fun seekBy(ms: Long) { player?.controls()?.skipTime(ms) }
    fun seekTo(ms: Long) { player?.controls()?.setTime(ms) }
    fun setVolume(v: Int) { player?.audio()?.setVolume(v) }
    fun timeMs(): Long = player?.status()?.time() ?: 0L
    fun lengthMs(): Long = player?.status()?.length() ?: 0L
    fun isPlaying(): Boolean = player?.status()?.isPlaying ?: false

    fun shutdown() {
        val p = player
        val f = factory
        // release() pode demorar um pouco: faz em outra thread para a tela não travar.
        Thread {
            runCatching { p?.controls()?.stop() }
            runCatching { p?.release() }
            runCatching { f?.release() }
        }.start()
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

// ------------------------------------------------------------------------------------------------
//  Ícones (desenhados, para não depender de fonte) e botões
// ------------------------------------------------------------------------------------------------
@Composable
private fun PlayPauseIcon(playing: Boolean, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            drawRoundRect(color, Offset(w * 0.2f, h * 0.14f), Size(w * 0.2f, h * 0.72f), CornerRadius(w * 0.05f))
            drawRoundRect(color, Offset(w * 0.6f, h * 0.14f), Size(w * 0.2f, h * 0.72f), CornerRadius(w * 0.05f))
        } else {
            val path = Path().apply {
                moveTo(w * 0.26f, h * 0.1f)
                lineTo(w * 0.92f, h * 0.5f)
                lineTo(w * 0.26f, h * 0.9f)
                close()
            }
            drawPath(path, color)
        }
    }
}

@Composable
private fun FullscreenIcon() {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val sw = w * 0.13f
        val l = w * 0.34f
        val m = w * 0.08f
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)
        seg(m, m, m + l, m); seg(m, m, m, m + l)
        seg(w - m, m, w - m - l, m); seg(w - m, m, w - m, m + l)
        seg(m, w - m, m + l, w - m); seg(m, w - m, m, w - m - l)
        seg(w - m, w - m, w - m - l, w - m); seg(w - m, w - m, w - m, w - m - l)
    }
}

@Composable
private fun VolumeIcon(level: Float) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val speaker = Path().apply {
            moveTo(w * 0.10f, h * 0.38f); lineTo(w * 0.30f, h * 0.38f); lineTo(w * 0.52f, h * 0.18f)
            lineTo(w * 0.52f, h * 0.82f); lineTo(w * 0.30f, h * 0.62f); lineTo(w * 0.10f, h * 0.62f); close()
        }
        drawPath(speaker, Color.White)
        val sw = w * 0.09f
        if (level <= 0f) {
            drawLine(Color.White, Offset(w * 0.66f, h * 0.36f), Offset(w * 0.92f, h * 0.64f), sw, StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.92f, h * 0.36f), Offset(w * 0.66f, h * 0.64f), sw, StrokeCap.Round)
        } else {
            drawArc(
                Color.White, -50f, 100f, false, Offset(w * 0.34f, h * 0.30f), Size(w * 0.36f, h * 0.40f),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            if (level > 50f) {
                drawArc(
                    Color.White, -50f, 100f, false, Offset(w * 0.30f, h * 0.14f), Size(w * 0.58f, h * 0.72f),
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun BackChevron() {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.16f
        drawLine(Color.White, Offset(w * 0.72f, h * 0.1f), Offset(w * 0.25f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.25f, h * 0.5f), Offset(w * 0.72f, h * 0.9f), sw, StrokeCap.Round)
    }
}

@Composable
private fun CircleButton(size: Dp, bg: Color, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun PillButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** O quadro do vídeo. Só esta função é redesenhada a cada quadro (o resto da tela não). */
@Composable
private fun VideoFrame(ctl: VlcCtl, modifier: Modifier) {
    val f = ctl.frame.value
    Box(modifier.background(Color.Black)) {
        if (f != null) {
            Image(bitmap = f, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

// ------------------------------------------------------------------------------------------------
//  Tela do player
// ------------------------------------------------------------------------------------------------
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
    if (!ctl.ok) {
        VlcMissing(onBack)
        return
    }

    val sid = epId ?: item.id

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
    var lastVolume by remember { mutableStateOf(100f) }
    var showServers by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val act = remember { longArrayOf(System.currentTimeMillis()) }          // última vez que o mouse/teclado mexeu
    val track = remember(sid) { longArrayOf(0L, 0L) }                       // [posição, duração] para salvar ao sair
    val firstOpen = remember(sid) { booleanArrayOf(true) }
    val focus = remember { FocusRequester() }
    val hasFrame by remember(ctl) { derivedStateOf { ctl.frame.value != null } }

    val fsNow by rememberUpdatedState(fullscreen)
    val fsCb by rememberUpdatedState(onFullscreen)
    val backCb by rememberUpdatedState(onBack)

    DisposableEffect(ctl) {
        onDispose {
            Store.savePos(sid, track[0], track[1])
            ctl.shutdown()
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

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
            delay(250)
            now = System.currentTimeMillis()
            pos = ctl.timeMs(); dur = ctl.lengthMs(); playing = ctl.isPlaying()
            if (pos > 0) track[0] = pos
            if (dur > 0) track[1] = dur
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
    val controlsVisible = !playing || showServers || drag != null || now - act[0] < 3_000

    val keys: (KeyEvent) -> Boolean = { e ->
        if (e.type != KeyEventType.KeyDown) {
            false
        } else {
            act[0] = System.currentTimeMillis()
            when (e.key) {
                Key.Spacebar -> { ctl.toggle(); true }
                Key.DirectionLeft -> { ctl.seekBy(-10_000); true }
                Key.DirectionRight -> { ctl.seekBy(10_000); true }
                Key.DirectionUp -> { volume = (volume + 5f).coerceAtMost(120f); true }
                Key.DirectionDown -> { volume = (volume - 5f).coerceAtLeast(0f); true }
                Key.F -> { fsCb(!fsNow); true }
                Key.Escape -> { if (fsNow) fsCb(false) else backCb(); true }
                else -> false
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent(keys)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Move || ev.type == PointerEventType.Press) {
                            val t = System.currentTimeMillis()
                            val wasHidden = t - act[0] > 3_000
                            act[0] = t
                            if (wasHidden) now = t   // mostra os controles na hora
                        }
                    }
                }
            },
    ) {
        // O filme. Um toque pausa/continua, dois toques alternam a tela cheia.
        VideoFrame(
            ctl,
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { fsCb(!fsNow) },
                    onTap = { ctl.toggle(); runCatching { focus.requestFocus() } },
                )
            },
        )

        // Antes do primeiro quadro: carregando (ou o motivo de não ter vídeo).
        if (!hasFrame) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    noServer -> Text("Nenhum servidor encontrado para este título.", color = Muted, fontSize = 16.sp)
                    allFailed -> Text("Nenhum servidor funcionou. Abra “Servidores” para tentar outro.", color = Muted, fontSize = 16.sp)
                    else -> {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(14.dp))
                        Text(status.ifEmpty { "Carregando o vídeo…" }, color = Muted, fontSize = 15.sp)
                    }
                }
            }
        } else if (!playing) {
            Box(
                Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) { PlayPauseIcon(false, Color.White, Modifier.size(38.dp)) }
        }

        // Topo: voltar + título
        AnimatedVisibility(
            visible = controlsVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(onClick = { backCb() }) {
                    BackChevron()
                    Spacer(Modifier.width(8.dp))
                    Text("Voltar", fontSize = 14.sp)
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (epLabel != null) Text(epLabel, color = Accent, fontSize = 13.sp)
                }
            }
        }

        // Base: barra de tempo e botões
        AnimatedVisibility(
            visible = controlsVisible, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(horizontal = 24.dp).padding(top = 30.dp, bottom = 14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(pos), fontSize = 13.sp)
                    Slider(
                        value = drag ?: (if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f),
                        onValueChange = { drag = it },
                        onValueChangeFinished = {
                            drag?.let { ctl.seekTo((it * dur).toLong()) }
                            drag = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Color(0x55FFFFFF),
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text(fmt(dur), fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CircleButton(50.dp, Accent, onClick = { ctl.toggle() }) {
                        PlayPauseIcon(playing, Color.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    PillButton(onClick = { ctl.seekBy(-10_000) }) { Text("«  10", fontSize = 14.sp) }
                    Spacer(Modifier.width(8.dp))
                    PillButton(onClick = { ctl.seekBy(10_000) }) { Text("10  »", fontSize = 14.sp) }
                    Spacer(Modifier.width(20.dp))
                    Box(
                        Modifier.clip(CircleShape).clickable {
                            if (volume > 0f) { lastVolume = volume; volume = 0f } else volume = lastVolume.coerceAtLeast(60f)
                        }.padding(6.dp),
                    ) { VolumeIcon(volume) }
                    Slider(
                        value = volume, onValueChange = { volume = it }, valueRange = 0f..120f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x55FFFFFF),
                        ),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    PillButton(onClick = { showServers = !showServers }) {
                        Text("Servidores", fontSize = 14.sp)
                        if (playable.isNotEmpty()) Text("  ${playable.size}", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    CircleButton(42.dp, Color(0x40FFFFFF), onClick = { fsCb(!fsNow) }) { FullscreenIcon() }
                }
            }
        }

        // Painel de servidores (por cima do filme, à direita)
        AnimatedVisibility(
            visible = showServers, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier.padding(end = 20.dp).width(380.dp).heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xEE121214)).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Servidores", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = { showServers = false }) { Text("Fechar") }
                }
                if (playable.isEmpty()) {
                    Text(if (searching) "Procurando…" else "Nenhum servidor.", color = Muted, modifier = Modifier.padding(8.dp))
                }
                LazyColumn {
                    items(playable) { p ->
                        val sel = p.second.url == current?.second?.url
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Color(0x33FFD60A) else Color.Transparent)
                                .clickable { failed = failed - (p.second.url ?: ""); current = p }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.first, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (sel) Accent else Color.White)
                                Text(p.second.label.replace('\n', ' ').take(90), color = Muted, fontSize = 12.sp, maxLines = 2)
                            }
                            if (sel) Text("tocando", color = Accent, fontSize = 12.sp)
                        }
                    }
                }
            }
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
