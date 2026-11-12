package io.github.rxiet.baiye.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val CatalogIcon: ImageVector = ImageVector.Builder("Catalog", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 6f); curveTo(9f, 4f, 5f, 3.8f, 2.5f, 5f)
        lineTo(2.5f, 19f); curveTo(5.8f, 17.5f, 9f, 18f, 12f, 20f)
        curveTo(15f, 18f, 18.2f, 17.5f, 21.5f, 19f)
        lineTo(21.5f, 5f); curveTo(19f, 3.8f, 15f, 4f, 12f, 6f)
        lineTo(12f, 20f)
    }
}.build()

val ShelfIcon: ImageVector = ImageVector.Builder("Shelf", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3f, 4f); lineTo(8f, 4f); lineTo(8f, 20f); lineTo(3f, 20f); close()
        moveTo(10f, 4f); lineTo(15f, 4f); lineTo(15f, 20f); lineTo(10f, 20f); close()
        moveTo(17f, 5f); lineTo(20f, 4.5f); lineTo(22f, 19.5f); lineTo(18.5f, 20f); close()
        moveTo(5f, 8f); lineTo(6f, 8f)
        moveTo(12f, 8f); lineTo(13f, 8f)
    }
}.build()
