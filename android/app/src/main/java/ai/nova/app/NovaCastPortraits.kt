package ai.nova.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Twelve original photorealistic adult character portraits, bundled locally.
 * Even when offline or without a deployed API, the source assets are included in the APK.
 * The 4x3 atlas was built from original AI-generated fictional character portraits.
 */
object NovaCastPortraits {
    private var cached: List<ImageBitmap>? = null

    fun find(context: Context, characterId: String): ImageBitmap? {
        val idx = characterId.takeLast(3).toIntOrNull()?.minus(101) ?: return null
        if (idx !in 0..11) return null
        if (cached == null) {
            synchronized(this) {
                if (cached == null) cached = decode(context)
            }
        }
        return cached?.getOrNull(idx)
    }

    private fun decode(context: Context): List<ImageBitmap> {
        return try {
            val b64 = context.assets.open("nova_cast_atlas.b64")
                .bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val atlas = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
            // A 320x240 WEBP: each cell is 80x80.
            if (atlas.width != 320 || atlas.height != 240) return emptyList()
            val portraits = (0..11).map { index ->
                val left = (index % 4) * 80
                val top = (index / 4) * 80
                Bitmap.createBitmap(atlas, left, top, 80, 80).asImageBitmap()
            }
            atlas.recycle()
            portraits
        } catch (_: Exception) {
            emptyList()
        }
    }
}
