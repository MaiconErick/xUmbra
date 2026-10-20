package com.maiconerick.xumbra.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream

object Images {
    /**
     * Phone photos are 4-12 MB; the model sees at most ~2k px anyway. Re-encode
     * anything big as a JPEG ≤ 2048 px (also turns HEIC into something every
     * provider accepts). Small images and GIFs go up untouched (null).
     */
    fun shrink(ctx: Context, uri: Uri, maxSide: Int = 2048, quality: Int = 88): ByteArray? = try {
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        val size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (mime == "image/gif" || (size in 1..700_000 && mime in setOf("image/jpeg", "image/png", "image/webp"))) null
        else {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) dec.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }
    } catch (_: Exception) { null }
}
