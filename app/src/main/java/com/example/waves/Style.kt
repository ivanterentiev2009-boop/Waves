package com.example.waves

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Настройки стиля. Общие для приложения и оверлея (один процесс). */
object Style {
    private var sp: SharedPreferences? = null
    var theme by mutableIntStateOf(0)            // 0 стекло, 1 минимализм, 2 AMOLED
    var accent by mutableIntStateOf(0xFFB69CFF.toInt())
    var glass by mutableFloatStateOf(0.14f)
    var corner by mutableIntStateOf(24)
    var overlayAlpha by mutableFloatStateOf(0.9f)
    var showViz by mutableStateOf(true)
    var refract by mutableFloatStateOf(1.3f)
    const val BUILD = "r31"
    var overlayRefract by mutableStateOf(false)   // шейдер в оверлее: красивее, но тяжелее
    var lightOverlay by mutableStateOf(true)   // облегчённый оверлей: без шейдера, быстрее
    var ovScale by mutableFloatStateOf(1f)     // редактор оверлея
    var ovCover by mutableStateOf(true)
    var ovEq by mutableStateOf(true)
    var ovLyric by mutableStateOf(true)
    var ovStart by mutableIntStateOf(1)        // 0 пузырёк, 1 компактный, 2 полный
    var layout by mutableIntStateOf(1)          // 0 классический, 1 как в стриминговых приложениях
    var bgArt by mutableStateOf<Uri?>(null)     // обложка на фоне (для размытия под панелями)
    var waveStyle by mutableIntStateOf(0)       // вид «Моей волны»
    var lyYandex by mutableStateOf(true)      // источники текстов
    var lyLrclib by mutableStateOf(true)
    var lyOvh by mutableStateOf(true)
    var asrEngine by mutableIntStateOf(1)      // 0 Vosk, 1 GigaAM
    var subsEnabled by mutableStateOf(false)   // распознавание текста (Vosk)
    var blurBehind by mutableStateOf(false)   // эксперимент, по умолчанию выключено

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("style", 0); sp = s
        theme = s.getInt("theme", 0); accent = s.getInt("accent", accent); glass = s.getFloat("glass", glass)
        corner = s.getInt("corner", corner); overlayAlpha = s.getFloat("oa", overlayAlpha); showViz = s.getBoolean("viz", true); refract = s.getFloat("refract", refract); blurBehind = s.getBoolean("bb", false); lightOverlay = s.getBoolean("lo", true)
        ovScale = s.getFloat("ovs", 1f); ovCover = s.getBoolean("ovc", true); ovEq = s.getBoolean("ove", true)
        ovLyric = s.getBoolean("ovl", true); ovStart = s.getInt("ovst", 1); overlayRefract = s.getBoolean("ovr", false); subsEnabled = s.getBoolean("subs", false); asrEngine = s.getInt("asr", 1)
        lyYandex = s.getBoolean("lyy", true); lyLrclib = s.getBoolean("lyl", true); lyOvh = s.getBoolean("lyo", true); waveStyle = s.getInt("ws3", 0).coerceIn(0, 2); layout = s.getInt("lay", 1)
    }
    fun save() {
        sp?.edit()?.putInt("theme", theme)?.putInt("accent", accent)?.putFloat("glass", glass)
            ?.putInt("corner", corner)?.putFloat("oa", overlayAlpha)?.putBoolean("viz", showViz)?.putFloat("refract", refract)?.putBoolean("bb", blurBehind)?.putBoolean("ovr", overlayRefract)?.putBoolean("subs", subsEnabled)?.putInt("asr", asrEngine)
            ?.putBoolean("lyy", lyYandex)?.putBoolean("lyl", lyLrclib)?.putBoolean("lyo", lyOvh)?.putInt("ws3", waveStyle)?.putInt("lay", layout)?.apply()
    }
}

/** Области экрана, внутри которых фон преломляется как стекло. */
object GlassRects { val map = mutableStateMapOf<String, Rect>() }

val glassCapable: Boolean get() = Build.VERSION.SDK_INT >= 33 && Style.theme == 0

private const val AGSL = """
uniform shader content;
uniform float4 rects[8];
uniform float radius;
uniform float k;

float sd(float2 p, float2 b, float r) {
  float2 q = abs(p) - b + float2(r);
  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 fc) {
  half4 col = content.eval(fc);
  for (int i = 0; i < 8; i++) {
    float4 r = rects[i];
    if (r.z > 1.0) {
      float2 h = r.zw * 0.5;
      float2 p = fc - (r.xy + h);
      float rad = min(radius, min(h.x, h.y));
      float d = sd(p, h, rad);
      if (d < 0.0) {
        float2 e = float2(1.0, 0.0);
        float2 n = normalize(float2(sd(p + e.xy, h, rad) - sd(p - e.xy, h, rad),
                                    sd(p + e.yx, h, rad) - sd(p - e.yx, h, rad)) + 0.00001);
        float depth = clamp(-d / (44.0 * k), 0.0, 1.0);
        float bend = pow(1.0 - depth, 2.5);
        float2 off = -n * bend * 60.0 * k - p * 0.10 * k;
        half4 c;
        c.r = content.eval(fc + off * 1.10).r;
        c.g = content.eval(fc + off).g;
        c.b = content.eval(fc + off * 0.90).b;
        c.a = 1.0;
        float rim = smoothstep(3.0, 0.0, -d);
        float lit = max(dot(n, normalize(float2(-0.7, -0.7))), 0.0);
        float lit2 = max(dot(n, normalize(float2(0.7, 0.7))), 0.0);
        c.rgb += half3(rim * (0.55 * lit + 0.25 * lit2));
        c.rgb += half3(0.07 * (1.0 - depth) + 0.04);
        col = c;
      }
    }
  }
  return col;
}
"""

@Composable
private fun Modifier.liquidLayer(): Modifier {
    val shader = remember { RuntimeShader(AGSL) }
    val radius = with(LocalDensity.current) { Style.corner.dp.toPx() }
    return this.graphicsLayer {
        val arr = FloatArray(32)
        GlassRects.map.values.take(8).forEachIndexed { i, r ->
            arr[i * 4] = r.left; arr[i * 4 + 1] = r.top; arr[i * 4 + 2] = r.width; arr[i * 4 + 3] = r.height
        }
        shader.setFloatUniform("rects", arr)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("k", Style.refract)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/** Панель: стекло (с преломлением на Android 13+) / минимализм / AMOLED. */
fun Modifier.panel(shape: Shape, strength: Float = 1f, refract: Boolean = false): Modifier =
    if (refract && glassCapable) composed {
        val id = remember { java.util.UUID.randomUUID().toString() }
        DisposableEffect(id) { onDispose { GlassRects.map.remove(id) } }
        Modifier.onGloballyPositioned { GlassRects.map[id] = it.boundsInRoot() }
            .clip(shape)
            .border(1.2.dp, Brush.linearGradient(listOf(Color.White.copy(0.75f), Color.White.copy(0.05f), Color.White.copy(0.4f))), shape)
    } else when (Style.theme) {
        0 -> this.clip(shape)
            .background(Color.Black.copy(alpha = 0.5f * strength))
            .background(Brush.verticalGradient(listOf(Color.White.copy(Style.glass + 0.10f), Color.White.copy(Style.glass * 0.4f))))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(0.55f), Color.White.copy(0.04f), Color.White.copy(0.25f))), shape)
        1 -> this.clip(shape).background(Color(0xFF17171C).copy(alpha = strength))
            .border(1.dp, Color.White.copy(0.07f), shape)
        3 -> this.clip(shape)          // матовое стекло: ровная приглушённая панель, без бликов и градиентов
            .background(Color(0xFF1B1B22).copy(alpha = ((0.50f + Style.glass * 0.6f).coerceAtMost(0.9f)) * strength))
            .border(1.dp, Color.White.copy(0.06f), shape)
        else -> this.clip(shape).background(Color.Black.copy(alpha = strength))
            .border(1.dp, Color.White.copy(0.10f), shape)
    }

@Composable
fun AppBackground(art: Uri?, content: @Composable BoxScope.() -> Unit) {
    SideEffect { Style.bgArt = art }
    val base = when (Style.theme) { 2 -> Color.Black; 1 -> Color(0xFF0B0B0E); else -> Color(0xFF08080D) }
    val haze = remember { HazeState() }
    CompositionLocalProvider(LocalHaze provides haze) {
    Box(Modifier.fillMaxSize().background(base)) {
      // фон — источник для размытия под панелями (только в матовом стекле)
      Box(Modifier.fillMaxSize().then(if (Style.theme == 3) Modifier.hazeSource(haze, zIndex = 0f) else Modifier)) {
        if (Style.theme == 0) {
            val layer = if (glassCapable) Modifier.fillMaxSize().liquidLayer() else Modifier.fillMaxSize()
            Box(layer) {
                val a = Color(Style.accent)
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(a.copy(0.55f), Color.Transparent), Offset(250f, 350f), 1100f)))
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFFFF8AD8).copy(0.30f), Color.Transparent), Offset(900f, 1500f), 900f)))
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF5CC8FF).copy(0.28f), Color.Transparent), Offset(100f, 2000f), 900f)))
                if (art != null) AsyncImage(art, null, Modifier.fillMaxSize().blur(if (glassCapable) 5.dp else 70.dp).alpha(if (glassCapable) 0.85f else 0.5f), contentScale = ContentScale.Crop)
            }
        } else if (Style.theme == 3) {
            MatteBackdrop(art, 16.dp, Modifier.fillMaxSize())
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val rnd = kotlin.random.Random(3)
                repeat(500) { drawCircle(Color.White.copy(0.03f), 1.dp.toPx(), Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)) }
            }
        }
      }
        content()
    }
    }
}

@Composable
private fun Modifier.refractSelf(radiusPx: Float?, alphaValue: Float): Modifier {
    val shader = remember { RuntimeShader(AGSL) }
    val radius = radiusPx ?: with(LocalDensity.current) { Style.corner.dp.toPx() }
    return this.graphicsLayer {
        this.alpha = alphaValue
        val arr = FloatArray(32); arr[2] = size.width; arr[3] = size.height   // одна область = вся панель
        shader.setFloatUniform("rects", arr)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("k", Style.refract)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/** Мягкая (маленькая и растянутая) обложка: выглядит размытой, но без дорогого blur-прохода. */
@Composable
fun SoftArt(art: Uri?, modifier: Modifier, alpha: Float = 1f) {
    val ctx = LocalContext.current
    AsyncImage(coil.request.ImageRequest.Builder(ctx).data(art).size(48).build(), null, modifier,
        contentScale = ContentScale.Crop, alpha = alpha)
}

/** Поверхность оверлея: на Android 13+ преломляет мягкую обложку трека, иначе матовое стекло. */
@Composable
fun OverlaySurface(shape: Shape, strength: Float, modifier: Modifier, art: Uri?, radiusPx: Float? = null,
                   content: @Composable BoxScope.() -> Unit) {
    if (!glassCapable) { Box(modifier.panel(shape, strength), content = content); return }
    val a = (if (Style.blurBehind) strength.coerceAtMost(0.45f) else strength).coerceIn(0.3f, 1f)
    Box(modifier.clip(shape)) {
        Box(Modifier.matchParentSize().refractSelf(radiusPx, a)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10)))
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
                Color(Style.accent).copy(0.5f), Color(0xFFFF8AD8).copy(0.3f), Color(0xFF5CC8FF).copy(0.3f)))))
            if (art != null) SoftArt(art, Modifier.fillMaxSize(), 0.8f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.25f)))
        }
        Box(Modifier.matchParentSize().border(1.2.dp, Brush.linearGradient(
            listOf(Color.White.copy(0.75f), Color.White.copy(0.05f), Color.White.copy(0.4f))), shape))
        content()
    }
}

/** Фон матового стекла: мягкий градиент и размытая обложка. */
@Composable
fun MatteBackdrop(art: Uri?, artBlur: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val tint = lerp(Color(Style.accent), Color.Black, 0.65f)
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(Color(0xFF111116)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(0.55f), Color(0xFF111116), tint.copy(0.30f)))))
        if (art != null) AsyncImage(art, null, Modifier.fillMaxSize().blur(artBlur).alpha(0.45f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.25f)))
    }
}

val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Панель. В теме «Матовое стекло» размывает всё, что находится под ней: фон, обложки и списки (библиотека Haze).
 * В остальных темах обычная панель.
 */
@Composable
fun Frosted(shape: Shape, modifier: Modifier = Modifier, refract: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val haze = LocalHaze.current
    if (Style.theme != 3 || haze == null) { Box(modifier.panel(shape, refract = refract), content = content); return }
    Box(modifier.clip(shape)
        .hazeEffect(haze) {
            backgroundColor = Color(0xFF1B1B22)
            blurRadius = 28.dp
            tints = listOf(HazeTint(Color.White.copy(0.08f + Style.glass * 0.20f)))
            noiseFactor = 0.04f
        }
        .border(1.dp, Color.White.copy(0.10f), shape), content = content)
}
