package io.github.myronl.ultiworldtv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The app's spinner: a teal arc chasing round a faint ring. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension / 10
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawCircle(Palette.Teal.copy(alpha = 0.18f), radius = (this.size.minDimension - stroke) / 2, style = Stroke(stroke))
        drawArc(
            Palette.Teal,
            startAngle = angle - 90f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

/** Fills placeholders with a soft highlight sweeping across, so a screen that's loading visibly is. */
@Composable
fun shimmer(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-500f, 1500f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "x")
    return Brush.linearGradient(
        listOf(Palette.Surface, Palette.SurfaceRaised, Palette.Surface),
        start = Offset(x, 0f),
        end = Offset(x + 500f, 250f)
    )
}

private val PlaceholderShape = RoundedCornerShape(10.dp)
private val LineShape = RoundedCornerShape(4.dp)

/** Where a video card is about to appear: its thumbnail and two lines of text, the same size as the real thing. */
@Composable
fun PlaceholderCard(brush: Brush, modifier: Modifier = Modifier) {
    Column(modifier.width(264.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(PlaceholderShape).background(brush))
        Spacer(Modifier.height(14.dp))
        PlaceholderLine(brush, Modifier.fillMaxWidth(0.85f), height = 14.dp)
        Spacer(Modifier.height(10.dp))
        PlaceholderLine(brush, Modifier.fillMaxWidth(0.5f), height = 11.dp)
    }
}

/** A line of text that hasn't loaded yet. */
@Composable
fun PlaceholderLine(brush: Brush, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    Box(modifier.height(height).clip(LineShape).background(brush))
}
