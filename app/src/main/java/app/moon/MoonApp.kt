package app.moon

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.imageDecoderEnabled
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import java.io.File

class MoonApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { App.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "images").toOkioPath())
                    .maxSizeBytes(768L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            // в Robolectric нет ImageDecoder — только для тестов
            .imageDecoderEnabled(Build.FINGERPRINT != "robolectric")
            .build()
}
