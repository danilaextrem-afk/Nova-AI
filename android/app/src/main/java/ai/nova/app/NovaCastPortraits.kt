package ai.nova.app
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
/** Individually generated original adult portraits; exact UUID matching avoids accidental impersonation. */
object NovaCastPortraits {
    private val cache = android.util.LruCache<String, ImageBitmap>(12)
    @Synchronized fun find(context: Context, characterId: String): ImageBitmap? {
        if (!Regex("00000000-0000-4000-8000-0000000001(0[1-9]|1[0-2])").matches(characterId)) return null
        cache.get(characterId)?.let { return it }
        return runCatching {
            context.assets.open("portraits/" + characterId.takeLast(3) + ".webp").use {
                BitmapFactory.decodeStream(it)?.asImageBitmap()?.also {bitmap->cache.put(characterId,bitmap)}
            }
        }.getOrNull()
    }
}
