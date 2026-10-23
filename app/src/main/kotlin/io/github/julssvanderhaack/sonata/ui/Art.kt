package io.github.julssvanderhaack.sonata.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.julssvanderhaack.sonata.library.Track
import io.github.julssvanderhaack.sonata.library.TrackIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Cover art from the file's embedded picture, or else the folder's cover.jpg
 * (found while scanning). Sizes are bucketed so list rows share one decode.
 */
object ArtCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<Long>()

    fun bucket(px: Int) = if (px <= 160) 160 else if (px <= 400) 400 else 900

    fun peek(id: Long, px: Int): Bitmap? = cache.get("$id:${bucket(px)}")

    suspend fun load(context: Context, id: Long, px: Int): Bitmap? {
        val size = bucket(px)
        val key = "$id:$size"
        cache.get(key)?.let { return it }
        synchronized(missing) { if (id in missing) return null }
        // a song just found by a scan reaches the list a moment before the index: wait for it, don't call it a miss
        var track = TrackIndex[id]
        var waited = 0
        while (track == null && waited < 3000) {
            delay(100)
            waited += 100
            track = TrackIndex[id]
        }
        if (track == null) return null
        val bmp = withContext(Dispatchers.IO) { decode(context, track, size) }
        if (bmp == null) synchronized(missing) { missing += id } else cache.put(key, bmp)
        return bmp
    }

    fun forget() {
        cache.evictAll()
        synchronized(missing) { missing.clear() }
    }

    private fun decode(context: Context, t: Track, size: Int): Bitmap? = runCatching {
        val embedded = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, t.uri.toUri())
                    embeddedPicture
                } finally {
                    release()
                }
            }
        }.getOrNull()
        if (embedded != null) return@runCatching sampled(size) { o -> BitmapFactory.decodeByteArray(embedded, 0, embedded.size, o) }
        val cover = t.coverUri?.toUri() ?: return@runCatching null
        sampled(size) { o -> context.contentResolver.openInputStream(cover)?.use { BitmapFactory.decodeStream(it, null, o) } }
    }.getOrNull()

    /** Decodes twice: bounds first, then at the largest power-of-two step that stays above [size]. */
    private fun sampled(size: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

@Composable
fun Art(trackId: Long?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(
        initialValue = trackId?.let { ArtCache.peek(it, px)?.asImageBitmap() },
        trackId, px,
    ) {
        value = trackId?.let { ArtCache.load(context, it, px)?.asImageBitmap() }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Rounded.MusicNote, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}
