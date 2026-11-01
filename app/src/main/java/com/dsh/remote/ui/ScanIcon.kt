package com.dsh.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A "scan" glyph drawn by hand: four corner brackets plus a horizontal scan line through the middle.
 *
 * Material's `QrCodeScanner` icon renders a QR pattern with no scan line, which reads as "QR code"
 * rather than "scan"; drawing it here keeps the shape exact and avoids depending on an icon whose
 * glyph differs between icon-set versions.
 */
@Composable
fun ScanIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    description: String? = "扫码配对",
) {
    val semantic = if (description != null) {
        Modifier.semantics { contentDescription = description }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.size(24.dp).then(semantic)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        val inset = w * 0.06f
        val arm = w * 0.26f
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(tint, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke, cap = StrokeCap.Round)
        }

        // Top-left, top-right, bottom-left, bottom-right brackets.
        line(left, top + arm, left, top)
        line(left, top, left + arm, top)
        line(right - arm, top, right, top)
        line(right, top, right, top + arm)
        line(left, bottom - arm, left, bottom)
        line(left, bottom, left + arm, bottom)
        line(right - arm, bottom, right, bottom)
        line(right, bottom, right, bottom - arm)

        // The scan line itself.
        line(left + arm * 0.55f, h / 2f, right - arm * 0.55f, h / 2f)
    }
}
