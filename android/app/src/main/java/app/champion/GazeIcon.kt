package app.champion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable fun GazeIcon(symbol: String, color: Color = Berry, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(vararg points: Float) {
                val path = Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]) }
                drawPath(path, color, style = stroke)
            }
            when (symbol) {
                "back" -> line(15f, 5f, 8f, 12f, 15f, 19f)
                "next" -> line(9f, 6f, 15f, 12f, 9f, 18f)
                "check" -> line(5f, 12f, 10f, 17f, 19f, 7f)
                "appearance" -> {
                    drawRoundRect(color, Offset(3f, 4f), Size(18f, 16f), CornerRadius(4f), style = stroke)
                    drawCircle(color, 2f, Offset(8f, 9f), style = stroke)
                    line(5f, 18f, 11f, 12f, 15f, 16f, 18f, 13f, 21f, 16f)
                }
                "background" -> {
                    drawRoundRect(color, Offset(6f, 2f), Size(12f, 20f), CornerRadius(3f), style = stroke)
                    line(10f, 5f, 14f, 5f)
                    line(10f, 18f, 14f, 18f)
                    line(9f, 12f, 11f, 14f, 15f, 10f)
                }
                "location" -> {
                    drawCircle(color, 7f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 2.5f, Offset(12f, 12f), style = stroke)
                    line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f)
                    line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f)
                }
                "history" -> {
                    drawRoundRect(color, Offset(4f, 4f), Size(16f, 17f), CornerRadius(3f), style = stroke)
                    line(8f, 9f, 16f, 9f); line(8f, 13f, 16f, 13f); line(8f, 17f, 12f, 17f)
                }
                "connection" -> {
                    drawCircle(color, 4f, Offset(6f, 12f), style = stroke)
                    drawCircle(color, 4f, Offset(18f, 12f), style = stroke)
                    line(9f, 12f, 15f, 12f)
                }
                else -> {
                    drawCircle(color, 6.5f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 2.5f, Offset(12f, 12f), style = stroke)
                    line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f)
                    line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f)
                    line(5f, 5f, 7f, 7f); line(17f, 17f, 19f, 19f)
                    line(5f, 19f, 7f, 17f); line(17f, 7f, 19f, 5f)
                }
            }
        }
    }
}
