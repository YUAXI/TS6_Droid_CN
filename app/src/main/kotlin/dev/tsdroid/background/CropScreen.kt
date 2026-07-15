package dev.tsdroid.background

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tsdroid.han.R
import kotlin.math.roundToInt

@Composable
fun CropScreen(
    bitmap: Bitmap,
    onConfirm: (left: Int, top: Int, right: Int, bottom: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    var imageWidth by remember { mutableFloatStateOf(0f) }
    var imageHeight by remember { mutableFloatStateOf(0f) }
    var cropOffset by remember { mutableStateOf(Offset.Zero) }
    var cropW by remember { mutableFloatStateOf(0f) }
    var cropH by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(imageWidth, imageHeight) {
        if (imageWidth > 0 && imageHeight > 0 && cropW == 0f) {
            cropW = imageWidth * 0.8f
            cropH = imageWidth * 0.8f
            cropOffset = Offset((imageWidth - cropW) / 2f, (imageHeight - cropH) / 2f)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
                Text(stringResource(R.string.crop_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                IconButton(onClick = {
                    val sx = bitmap.width.toFloat() / imageWidth
                    val sy = bitmap.height.toFloat() / imageHeight
                    onConfirm(
                        (cropOffset.x * sx).roundToInt().coerceAtLeast(0),
                        (cropOffset.y * sy).roundToInt().coerceAtLeast(0),
                        ((cropOffset.x + cropW) * sx).roundToInt().coerceAtMost(bitmap.width),
                        ((cropOffset.y + cropH) * sy).roundToInt().coerceAtMost(bitmap.height)
                    )
                }) {
                    Icon(Icons.Default.Check, null, tint = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom == 1f) {
                                    val maxX = (imageWidth - cropW).coerceAtLeast(0f)
                                    val maxY = (imageHeight - cropH).coerceAtLeast(0f)
                                    cropOffset = Offset(
                                        (cropOffset.x + pan.x).coerceIn(0f, maxX),
                                        (cropOffset.y + pan.y).coerceIn(0f, maxY)
                                    )
                                } else {
                                    val newW = (cropW * zoom).coerceIn(60f, imageWidth)
                                    val newH = (cropH * zoom).coerceIn(60f, imageHeight)
                                    val cx = cropOffset.x + cropW / 2f
                                    val cy = cropOffset.y + cropH / 2f
                                    cropW = newW
                                    cropH = newH
                                    cropOffset = Offset(
                                        (cx - newW / 2f).coerceIn(0f, (imageWidth - newW).coerceAtLeast(0f)),
                                        (cy - newH / 2f).coerceIn(0f, (imageHeight - newH).coerceAtLeast(0f))
                                    )
                                }
                            }
                        }
                ) {
                    imageWidth = size.width
                    imageHeight = size.height
                    if (cropW <= 0f) return@Canvas

                    drawImage(imageBitmap, dstSize = androidx.compose.ui.unit.IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    drawRect(color = Color.Black.copy(alpha = 0.4f))

                    val sx = bitmap.width.toFloat() / size.width
                    val sy = bitmap.height.toFloat() / size.height
                    drawImage(
                        imageBitmap,
                        srcOffset = androidx.compose.ui.unit.IntOffset((cropOffset.x * sx).roundToInt().coerceAtLeast(0), (cropOffset.y * sy).roundToInt().coerceAtLeast(0)),
                        srcSize = androidx.compose.ui.unit.IntSize((cropW * sx).roundToInt().coerceAtLeast(1), (cropH * sy).roundToInt().coerceAtLeast(1)),
                        dstOffset = androidx.compose.ui.unit.IntOffset(cropOffset.x.roundToInt(), cropOffset.y.roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(cropW.roundToInt(), cropH.roundToInt())
                    )

                    drawRect(color = Color.White, topLeft = cropOffset, size = Size(cropW, cropH), style = Stroke(width = 2.dp.toPx()))

                    for (i in 1..2) {
                        val lx = cropOffset.x + cropW * i / 3f
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(lx, cropOffset.y), Offset(lx, cropOffset.y + cropH), strokeWidth = 1.dp.toPx())
                        val ly = cropOffset.y + cropH * i / 3f
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(cropOffset.x, ly), Offset(cropOffset.x + cropW, ly), strokeWidth = 1.dp.toPx())
                    }

                    val hs = 16.dp.toPx()
                    listOf(
                        cropOffset,
                        Offset(cropOffset.x + cropW, cropOffset.y),
                        Offset(cropOffset.x, cropOffset.y + cropH),
                        Offset(cropOffset.x + cropW, cropOffset.y + cropH)
                    ).forEach { drawCircle(Color.White, radius = hs / 2, center = it) }
                }
            }

            Text(
                text = stringResource(R.string.crop_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}
