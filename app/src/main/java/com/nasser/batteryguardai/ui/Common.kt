package com.nasser.batteryguardai.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasser.batteryguardai.engine.f1

val Emerald = Color(0xFF10B981)
val Warn = Color(0xFFF59E0B)
val Bad = Color(0xFFEF4444)

private val DarkC = darkColorScheme(primary = Emerald, background = Color(0xFF0D0F0E), surface = Color(0xFF161A18),
    surfaceVariant = Color(0xFF1F2522), onBackground = Color.White, onSurface = Color.White)
private val LightC = lightColorScheme(primary = Color(0xFF0B8F63), background = Color(0xFFF4F7F5), surface = Color.White,
    surfaceVariant = Color(0xFFE6EEE9))

@Composable fun BgTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkC else LightC, content = content)

@Composable fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable fun Panel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Card(modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable fun StatRow(a: Pair<String, String>, b: Pair<String, String>) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(a.first, a.second, Modifier.weight(1f)); Stat(b.first, b.second, Modifier.weight(1f))
    }

@Composable fun Ring(level: Int, charging: Boolean) {
    val p by animateFloatAsState(level.coerceAtLeast(0) / 100f, tween(900), label = "ring")
    val alpha = if (charging) rememberInfiniteTransition(label = "pulse")
        .animateFloat(.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a").value else 1f
    val color = when { level <= 15 && !charging -> Bad; level <= 30 && !charging -> Warn; else -> Emerald }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val st = Stroke(width = 28f, cap = StrokeCap.Round)
            drawArc(track, -90f, 360f, false, style = st)
            drawArc(color.copy(alpha = alpha), -90f, 360f * p, false, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$level%", fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Text(if (charging) "قيد الشحن" else "على البطارية", fontSize = 14.sp, color = color)
        }
    }
}

@Composable fun LineChart(values: List<Float>, lo: Float, hi: Float, color: Color, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        for (i in 0..4) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 2f)
        if (values.size < 2 || hi <= lo) return@Canvas
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1 - (v - lo) / (hi - lo)).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 5f, join = StrokeJoin.Round))
    }
}

fun hm(h: Double?): String = if (h == null) "غير متاح" else "${h.toInt()} س ${((h % 1) * 60).toInt()} د"
fun dur(ms: Long): String = hm(ms / 3_600_000.0)
fun pct(r: Double?): String = if (r == null) "غير متاح" else "${f1(r)}% / س"
