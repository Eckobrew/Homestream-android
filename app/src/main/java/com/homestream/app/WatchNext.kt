package com.homestream.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** Native Watch Next provider integration; never activated on phones. */
object WatchNext {
    private const val JOB_ID = 3101
    private val lock = Mutex()
    private val recentLocalChanges = mutableMapOf<String, Long>()
    private val uri = TvContract.WatchNextPrograms.CONTENT_URI
    private data class Row(val id: Long, val key: String, val position: Long, val browsable: Boolean)

    fun scope(settings: ServerSettings): String = MessageDigest.getInstance("SHA-256")
        .digest("${settings.serverIdentity}|${settings.profile}".toByteArray()).joinToString("") { "%02x".format(it) }

    fun schedule(context: Context) {
        if (!context.isTelevision()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(context, WatchNextJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
    }

    fun refresh(context: Context) {
        if (!context.isTelevision()) return
        (context.applicationContext as? HomeStreamApplication)?.playbackReports?.launch {
            runCatching { sync(context.applicationContext) }
                .onFailure { Log.w("HomeStreamWatchNext", "Launcher sync unavailable: ${it.javaClass.simpleName}") }
        }
    }

    fun onDismissed(context: Context, rowId: Long) {
        if (!context.isTelevision() || rowId < 0) return
        val key = dismissed(context).getString("row-$rowId", null) ?: return
        hide(context, key)
    }

    private fun rows(context: Context): List<Row> {
        val result = mutableListOf<Row>()
        context.contentResolver.query(uri, arrayOf("_id", TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == context.packageName) {
                result.add(Row(cursor.getLong(0), cursor.getString(2).orEmpty(), cursor.getLong(3), cursor.getInt(4) != 0))
            }
        }
        return result
    }

    private fun group(media: Media): String = media.json.optString("seriesId").takeIf { it.isNotBlank() }
        ?.let { "series:$it" } ?: "movie:${media.playbackId}"

    private fun eligible(media: Media, position: Long, duration: Long): Boolean {
        if (duration <= 0 || media.isDownload || media.isVirtual || media.json.optBoolean("played") || media.type !in listOf("Movie", "Episode")) return false
        val started = if (media.type == "Episode") 120000L else minOf(120000L, (duration * 0.03).toLong())
        return position >= started && position < (duration * 0.95).toLong() && duration - position > 10000L
    }

    private fun dismissed(context: Context) = context.getSharedPreferences("watch-next", Context.MODE_PRIVATE)
    private fun hidden(context: Context, key: String) = key in dismissed(context).getStringSet("hidden", emptySet()).orEmpty()
    private fun hide(context: Context, key: String) {
        val prefs = dismissed(context)
        prefs.edit().putStringSet("hidden", prefs.getStringSet("hidden", emptySet()).orEmpty() + key).apply()
    }

    private fun put(context: Context, settings: ServerSettings, media: Media, position: Long, duration: Long, existing: Row?, engagement: Long) {
        val key = "${scope(settings)}:${group(media)}"
        if (existing?.browsable == false) { hide(context, key); return }
        if (hidden(context, key)) return
        val link = Uri.Builder().scheme("homestream").authority("watch").appendQueryParameter("id", media.playbackId)
            .appendQueryParameter("scope", scope(settings)).build()
        val intent = Intent(Intent.ACTION_VIEW, link, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val artwork = HomeStreamApi(settings).absolute(media.backdrop.ifBlank { media.poster })
        val values = ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, key)
            put(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, link.toString())
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, if (media.type == "Episode") TvContract.WatchNextPrograms.TYPE_TV_EPISODE else TvContract.WatchNextPrograms.TYPE_MOVIE)
            put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, media.json.optString("seriesName").ifBlank { media.title })
            put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, if (media.type == "Episode") media.title else "")
            put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, media.overview)
            put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, artwork)
            put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, if (media.backdrop.isNotBlank()) TvContract.WatchNextPrograms.ASPECT_RATIO_16_9 else TvContract.WatchNextPrograms.ASPECT_RATIO_2_3)
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intent.toUri(Intent.URI_INTENT_SCHEME))
            put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, engagement)
            if (media.type == "Episode") {
                put(TvContract.WatchNextPrograms.COLUMN_SERIES_ID, "${scope(settings)}:${media.json.optString("seriesId")}")
                put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, media.json.optInt("seasonNumber", 1).toString())
                put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, media.json.optInt("episodeNumber", 1).toString())
            }
        }
        if (existing == null) context.contentResolver.insert(uri, values)?.let {
            dismissed(context).edit().putString("row-${ContentUris.parseId(it)}", key).apply()
        } else context.contentResolver.update(ContentUris.withAppendedId(uri, existing.id), values, null, null)
    }

    suspend fun sync(context: Context) {
        if (!context.isTelevision()) return
        val settings = ServerSettings(context)
        val identity = scope(settings)
        val connected = settings.token.isNotBlank()
        lock.withLock {
            rows(context).filter { !connected || !it.key.startsWith("$identity:") }.forEach {
                context.contentResolver.delete(ContentUris.withAppendedId(uri, it.id), null, null)
            }
        }
        if (!connected) return
        // Network I/O never blocks local stop/progress updates to the launcher.
        // A failed request preserves cards rather than treating failure as empty history.
        val items = HomeStreamApi(settings).call("continue-watching").mediaList("items")
        lock.withLock {
            if (scope(ServerSettings(context)) != identity || ServerSettings(context).token.isBlank()) return@withLock
            val existing = rows(context)
            val now = System.currentTimeMillis()
            recentLocalChanges.entries.removeAll { now - it.value > 120000L }
            val wanted = items.filter { eligible(it, it.resumeMs, ticksToMs(it.json.optLong("totalDurationTicks"))) }
                .distinctBy { group(it) }.take(16)
            val keys = wanted.map { "$identity:${group(it)}" }.toSet() + recentLocalChanges.keys
            existing.filter { it.key !in keys }.forEach {
                context.contentResolver.delete(ContentUris.withAppendedId(uri, it.id), null, null)
            }
            wanted.forEach { media ->
                val key = "$identity:${group(media)}"
                if (key in recentLocalChanges) return@forEach
                val row = existing.firstOrNull { it.key == key }
                if (row != null && !row.browsable) hide(context, row.key)
                if (row == null || row.position != media.resumeMs) put(context, settings, media, media.resumeMs,
                    ticksToMs(media.json.optLong("totalDurationTicks")), row, now)
            }
            Log.i("HomeStreamWatchNext", "Launcher history synchronized (${wanted.size} items)")
        }
    }

    suspend fun record(context: Context, media: Media, position: Long, duration: Long, identity: String) {
        if (!context.isTelevision()) return
        lock.withLock {
            val settings = ServerSettings(context)
            if (scope(settings) != identity || settings.token.isBlank()) return@withLock
            val key = "$identity:${group(media)}"
            recentLocalChanges[key] = System.currentTimeMillis()
            val existing = rows(context).firstOrNull { it.key == key }
            if (!eligible(media, position, duration)) {
                existing?.let { context.contentResolver.delete(ContentUris.withAppendedId(uri, it.id), null, null) }
                return@withLock
            }
            put(context, settings, media, position, duration, existing, System.currentTimeMillis())
        }
    }
}

class WatchNextJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            var retry = false
            try { WatchNext.sync(applicationContext) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { retry = true; Log.w("HomeStreamWatchNext", "Launcher sync unavailable: ${e.javaClass.simpleName}") }
            finally { if (isActive) jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class WatchNextReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.isTelevision()) return
        if (intent.action == TvContract.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED) {
            WatchNext.onDismissed(context, intent.getLongExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, -1))
        } else if (intent.action == TvContract.ACTION_INITIALIZE_PROGRAMS) {
            WatchNext.schedule(context)
            WatchNext.refresh(context)
        }
    }
}
