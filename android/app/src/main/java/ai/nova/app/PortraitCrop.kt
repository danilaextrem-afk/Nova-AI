package ai.nova.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun decodePortrait(resolver: ContentResolver, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        // Some document providers return a pipe instead of a seekable asset descriptor.
        val decoded = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > 1024) {
                    val scale = 1024f / largest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        }.getOrNull()
        if (decoded != null) return decoded
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1024) sampleSize *= 2
    return resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }
}

internal fun portraitCrop(width: Int, height: Int, zoom: Float, horizontal: Float, vertical: Float): IntRect {
    val baseWidth = minOf(width.toFloat(), height * 2f / 3f)
    val cropWidth = (baseWidth / zoom).roundToInt().coerceIn(1, width)
    val cropHeight = (baseWidth * 1.5f / zoom).roundToInt().coerceIn(1, height)
    val left = ((width - cropWidth) * horizontal).roundToInt().coerceIn(0, width - cropWidth)
    val top = ((height - cropHeight) * vertical).roundToInt().coerceIn(0, height - cropHeight)
    return IntRect(left, top, left + cropWidth, top + cropHeight)
}

@Composable
internal fun PortraitCropDialog(
    uri: Uri,
    resolver: ContentResolver,
    onDismiss: () -> Unit,
    onAccept: (Bitmap, IntRect) -> Unit
) {
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var zoom by rememberSaveable(uri.toString()) { mutableFloatStateOf(1f) }
    var horizontal by rememberSaveable(uri.toString()) { mutableFloatStateOf(0.5f) }
    var vertical by rememberSaveable(uri.toString()) { mutableFloatStateOf(0.5f) }
    var rightsConfirmed by rememberSaveable(uri.toString()) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        try {
            bitmap = withContext(Dispatchers.IO) { decodePortrait(resolver, uri) }
            failed = bitmap == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("NovaPortrait", "Portrait decode failed: " + error.javaClass.simpleName)
            failed = true
        }
    }

    val photo = bitmap
    val crop = photo?.let { portraitCrop(it.width, it.height, zoom, horizontal, vertical) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обрезать портрет") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    failed -> Text("Не удалось открыть изображение. Выбери другое фото.")
                    photo == null || crop == null -> CircularProgressIndicator()
                    else -> {
                        val image = remember(photo) { photo.asImageBitmap() }
                        Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            // Drag to move the frame, pinch to zoom: no separate position sliders on small screens.
                            Canvas(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp))
                                .pointerInput(photo) {
                                    detectTransformGestures { _, pan, gesture, _ ->
                                        zoom = (zoom * gesture).coerceIn(1f, 2.6f)
                                        val c = portraitCrop(photo.width, photo.height, zoom, horizontal, vertical)
                                        val freeX = (photo.width - c.width).coerceAtLeast(1)
                                        val freeY = (photo.height - c.height).coerceAtLeast(1)
                                        // Image pixels per screen pixel inside the preview.
                                        val scale = c.width / size.width.toFloat()
                                        horizontal = (horizontal - pan.x * scale / freeX).coerceIn(0f, 1f)
                                        vertical = (vertical - pan.y * scale / freeY).coerceIn(0f, 1f)
                                    }
                                }) {
                                drawImage(image, srcOffset = IntOffset(crop.left, crop.top),
                                    srcSize = IntSize(crop.width, crop.height),
                                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                            }
                        }
                        Text("Двигай фото пальцем, увеличивай щипком или ползунком.", style = MaterialTheme.typography.labelSmall,
                            color = NovaDesign.Muted)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = rightsConfirmed, onCheckedChange = { rightsConfirmed = it })
                            Text("У меня есть право использовать фото. Все изображённые люди совершеннолетние и дали согласие. Откровенного контента нет.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Масштаб", style = MaterialTheme.typography.labelMedium)
                        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..2.6f)
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Button(enabled = photo != null && crop != null && rightsConfirmed, onClick = {
                    if (photo != null && crop != null) onAccept(photo, crop)
                }) { Text("Сохранить портрет") }
                if (photo != null && !rightsConfirmed) Text("Сначала отметь согласие выше", style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
