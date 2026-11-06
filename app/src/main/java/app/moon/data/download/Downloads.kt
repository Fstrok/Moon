package app.moon.data.download

import app.moon.data.Chapter
import app.moon.data.MangaItem
import app.moon.data.site.NudeMoonApi
import app.moon.data.store.AppJson
import app.moon.data.net.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

@Serializable
data class DownloadMeta(
    val manga: MangaItem,
    val chapter: Chapter,
    val pages: Int,
    val complete: Boolean,
    val time: Long,
    val sizeBytes: Long = 0,
)

sealed interface DlState {
    data object Queued : DlState
    data class Running(val done: Int, val total: Int) : DlState
    data class Failed(val message: String) : DlState
    data object Done : DlState
}

/** Офлайн-загрузки глав в приватную папку приложения (в галерею не попадают). */
class Downloads(
    private val root: File,
    private val api: () -> NudeMoonApi,
    private val client: () -> OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, DlState>>(emptyMap())
    val states: StateFlow<Map<String, DlState>> = _states.asStateFlow()

    private val _metas = MutableStateFlow<List<DownloadMeta>>(emptyList())
    val metas: StateFlow<List<DownloadMeta>> = _metas.asStateFlow()

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val semaphore = Semaphore(2)

    init {
        scope.launch(Dispatchers.IO) { rescan() }
    }

    fun dirFor(chapterUrl: String): File = File(root, hash(chapterUrl))

    fun localPages(chapterUrl: String): List<File>? {
        val dir = dirFor(chapterUrl)
        val meta = readMeta(dir) ?: return null
        if (!meta.complete) return null
        val files = dir.listFiles { f -> f.name.startsWith("p") }?.sortedBy { it.name } ?: return null
        return files.takeIf { it.size == meta.pages }
    }

    fun isDownloaded(chapterUrl: String) = _metas.value.any { it.chapter.url == chapterUrl && it.complete }

    fun enqueue(manga: MangaItem, chapters: List<Chapter>) {
        chapters.forEach { ch ->
            if (isDownloaded(ch.url) || jobs[ch.url]?.isActive == true) return@forEach
            _states.update { it + (ch.url to DlState.Queued) }
            jobs[ch.url] = scope.launch(Dispatchers.IO) {
                semaphore.withPermit { download(manga, ch) }
            }
        }
    }

    fun cancel(chapterUrl: String) {
        jobs.remove(chapterUrl)?.cancel()
        _states.update { it - chapterUrl }
        val dir = dirFor(chapterUrl)
        if (readMeta(dir)?.complete != true) dir.deleteRecursively()
    }

    fun delete(chapterUrl: String) {
        jobs.remove(chapterUrl)?.cancel()
        dirFor(chapterUrl).deleteRecursively()
        _states.update { it - chapterUrl }
        _metas.update { list -> list.filterNot { it.chapter.url == chapterUrl } }
    }

    fun deleteManga(mangaUrl: String) {
        _metas.value.filter { it.manga.url == mangaUrl }.forEach { delete(it.chapter.url) }
    }

    private suspend fun download(manga: MangaItem, ch: Chapter) {
        val dir = dirFor(ch.url).apply { mkdirs() }
        try {
            val urls = api().pages(ch.url)
            _states.update { it + (ch.url to DlState.Running(0, urls.size)) }
            var size = 0L
            urls.forEachIndexed { i, url ->
                val ext = url.substringAfterLast('.', "jpg").substringBefore('?').take(4).lowercase()
                    .let { if (it in setOf("jpg", "jpeg", "png", "webp", "gif")) it else "jpg" }
                val target = File(dir, "p%04d.%s".format(i, ext))
                if (!target.exists() || target.length() == 0L) {
                    val resp = client().newCall(Request.Builder().url(url).build()).await()
                    withContext(Dispatchers.IO) {
                        resp.use { r ->
                            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                            val tmp = File(dir, target.name + ".part")
                            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            tmp.renameTo(target)
                        }
                    }
                }
                size += target.length()
                _states.update { it + (ch.url to DlState.Running(i + 1, urls.size)) }
            }
            val meta = DownloadMeta(manga, ch, urls.size, true, System.currentTimeMillis(), size)
            File(dir, META).writeText(AppJson.encodeToString(DownloadMeta.serializer(), meta))
            _metas.update { list -> listOf(meta) + list.filterNot { it.chapter.url == ch.url } }
            _states.update { it + (ch.url to DlState.Done) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _states.update { it + (ch.url to DlState.Failed(e.message ?: e.javaClass.simpleName)) }
        } finally {
            jobs.remove(ch.url)
        }
    }

    private fun rescan() {
        val list = root.listFiles()?.mapNotNull { readMeta(it) }?.filter { it.complete } ?: emptyList()
        _metas.value = list.sortedByDescending { it.time }
    }

    private fun readMeta(dir: File): DownloadMeta? = runCatching {
        AppJson.decodeFromString(DownloadMeta.serializer(), File(dir, META).readText())
    }.getOrNull()

    fun totalBytes(): Long = _metas.value.sumOf { it.sizeBytes }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private companion object {
        const val META = "meta.json"
    }
}
