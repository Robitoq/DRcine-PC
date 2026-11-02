package com.drcine.pc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drcine.app.Item
import com.drcine.app.Net
import com.drcine.app.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

val Accent = Color(0xFFFFD60A)
val AppBg = Color(0xFF070A12)
val CardBg = Color(0xFF0E1422)
val Line = Color(0x1AFFFFFF)
val Muted = Color(0xFFA9A9B2)

@Composable
fun DrcineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.Black,
            background = AppBg, onBackground = Color.White,
            surface = CardBg, onSurface = Color.White,
            surfaceVariant = Color(0xFF1B2335), onSurfaceVariant = Muted,
            secondaryContainer = Color(0xFF232C40), onSecondaryContainer = Color.White,
        ),
        content = content,
    )
}

@Composable
fun Logo(size: TextUnit = 24.sp) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Black)) { append("DR") }
            withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Black)) { append("cine") }
        },
        fontSize = size,
    )
}

/** Baixa e guarda as imagens (pôsteres, fundos) usando o mesmo cliente de rede do resto do app. */
object Images {
    private val mem = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 300
    }

    fun peek(url: String): ImageBitmap? = synchronized(mem) { mem[url] }

    suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                Net.api.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null
                    else org.jetbrains.skia.Image.makeFromEncoded(r.body!!.bytes()).toComposeImageBitmap()
                }
            }.getOrNull()
        }
        if (bmp != null) synchronized(mem) { mem[url] = bmp }
        return bmp
    }
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(url?.let { Images.peek(it) }) }
    LaunchedEffect(url) {
        if (bmp == null && url != null) bmp = Images.load(url)
    }
    Box(modifier.background(Color(0xFF1C1C20))) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = scale)
        }
    }
}

@Composable
fun PosterCard(item: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pr = remember(item.id) { Store.progress(item) }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).border(1.dp, Line, shape)) {
            RemoteImage(item.poster, Modifier.fillMaxSize())
            Text(
                if (item.type == "series") "SÉRIE" else "FILME",
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (pr != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxWidth(pr / 100f).fillMaxHeight().background(Accent))
                }
            }
        }
        Text(
            item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        if (item.year.isNotBlank()) {
            Text(item.year, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

/** Pílula de informação (ano, duração, gênero…). */
@Composable
fun Chip(text: String, highlight: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (highlight) Accent else Color(0xFFE6E8EE),
        modifier = Modifier.clip(shape)
            .background(if (highlight) Color(0x1FFFD60A) else Color(0x14FFFFFF))
            .border(1.dp, if (highlight) Color(0x66FFD60A) else Line, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Título de seção com a barrinha colorida na frente. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Botão de temporada (T1, T2…). */
@Composable
fun SeasonPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier.clip(shape)
            .background(if (selected) Accent else Color(0x14FFFFFF))
            .border(1.dp, if (selected) Accent else Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** Uma prateleira: título + fileira de pôsteres que rola (com botões ‹ › para quem usa mouse). */
@Composable
fun ItemRow(title: String, list: List<Item>, onOpen: (Item) -> Unit) {
    val st = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch { st.animateScrollToItem((st.firstVisibleItemIndex - 4).coerceAtLeast(0)) }
            }) { Text("‹", fontSize = 20.sp) }
            TextButton(onClick = {
                scope.launch { st.animateScrollToItem((st.firstVisibleItemIndex + 4).coerceAtMost((list.size - 1).coerceAtLeast(0))) }
            }) { Text("›", fontSize = 20.sp) }
        }
        LazyRow(
            state = st,
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list) { PosterCard(it, Modifier.width(150.dp)) { onOpen(it) } }
        }
    }
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
fun Loading(text: String = "Carregando…") {
    Centered {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(14.dp))
        Text(text, color = Muted)
    }
}
