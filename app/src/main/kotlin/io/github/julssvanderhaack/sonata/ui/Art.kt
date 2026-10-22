package io.github.julssvanderhaack.sonata.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
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
import io.github.julssvanderhaack.sonata.library.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover art from the file's embedded picture (or the folder's cover.jpg, which
 * MediaStore's thumbnailer also looks for). Sizes are bucketed so list rows
 * share one decode.
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
        val bmp = withContext(Dispatchers.IO) { decode(context, id, size) }
        if (bmp == null) synchronized(missing) { missing += id } else cache.put(key, bmp)
        return bmp
    }

    fun forget() {
        cache.evictAll()
        synchronized(missing) { missing.clear() }
    }

    private fun decode(context: Context, id: Long, size: Int): Bitmap? = runCatching {
        val uri = Scanner.uriOf(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(size, size), null)
        } else {
            val bytes = MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    embeddedPicture
                } finally {
                    release()
                }
            } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()
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
