package com.maiconerick.xumbra

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import com.maiconerick.xumbra.data.Notifier
import com.maiconerick.xumbra.data.Prefs
import com.maiconerick.xumbra.data.Umbra
import com.maiconerick.xumbra.service.HubService
import com.maiconerick.xumbra.ui.theme.Palettes
import com.maiconerick.xumbra.ui.theme.Spook
import com.maiconerick.xumbra.ui.theme.SpookPalette

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        Spook.palette = (if (prefs.theme == "custom") SpookPalette.custom(prefs.customTheme) else null)
            ?: Palettes.firstOrNull { it.id == prefs.theme } ?: Palettes[0]
        Spook.animations = prefs.animations
        Spook.homeAmbient = prefs.homeAmbient
        Spook.chatAmbient = prefs.chatAmbient
        Spook.homeTune = prefs.tune("home")
        Spook.chatTune = prefs.tune("chat")
        Spook.batPet = prefs.batPet
        Spook.batPetSize = prefs.batPetSize
        Umbra.init(this)
        Notifier.init(this)
        HubService.sync(this)
    }

    /** Coil: images from the Pi carry the API token; persona icons/backgrounds and chat media are cached on disk. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(ImageDecoderDecoder.Factory()) }
        .okHttpClient {
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val t = prefs.token
                val r = chain.request()
                chain.proceed(if (t.isEmpty()) r else r.newBuilder().header("Authorization", "Bearer $t").build())
            }.build()
        }
        .respectCacheHeaders(false)
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(true)
        .build()

    companion object {
        lateinit var instance: App
        lateinit var prefs: Prefs
    }
}
