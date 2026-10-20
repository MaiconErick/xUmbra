package com.maiconerick.xumbra.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.Umbra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Saves an image from the Pi into Pictures/xUmbra. */
object MediaSave {
    fun save(ctx: Context, url: String) = Umbra.launch {
        try {
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "image.png" }
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).apply { App.prefs.token.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }.build()
                val bytes = Net.client.newCall(req).execute().use { it.body!!.bytes() }
                val mime = when (name.substringAfterLast('.').lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/png" }
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/xUmbra")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: error("no media store")
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            }
            Umbra.banner.value = "💾 Saved to Pictures/xUmbra"
        } catch (e: Exception) { Umbra.banner.value = "Couldn't save: ${e.message}" }
    }
}
