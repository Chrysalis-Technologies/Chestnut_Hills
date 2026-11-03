package com.chrysalis.treeid.data.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class MapInfo(val name: String, val attribution: String, val minZoom: Int, val maxZoom: Int)
data class MapTile(val x: Int, val y: Int, val zoom: Int, val bitmap: Bitmap)

/** Read-only raster MBTiles. All public disk operations are called on Dispatchers.IO. */
class OfflineMapStore(private val context: Context) : AutoCloseable {
    private val directory = File(context.filesDir, "offline-maps")
    private val preferences = context.getSharedPreferences("offline-maps", Context.MODE_PRIVATE)
    private var database: SQLiteDatabase? = null
    private var info: MapInfo? = null
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Synchronized fun openSaved(): MapInfo? {
        if (database != null) return info
        val name = preferences.getString("active", null) ?: return null
        require(name.matches(Regex("map-[a-f0-9-]+\\.mbtiles"))) { "Invalid saved map name" }
        val db = open(File(directory, name))
        try { info = validate(db); database = db } catch (e: Exception) { db.close(); throw e }
        return info
    }

    suspend fun import(uri: Uri): MapInfo = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { importStream(it) }
            ?: error("Cannot read the selected map")
    }

    internal suspend fun importStream(input: InputStream): MapInfo = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create map folder" }
        val candidate = File(directory, "map-${UUID.randomUUID()}.mbtiles")
        var adopted = false
        try {
            candidate.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1024L * 1024 * 1024) { "Map exceeds the 1 GiB import limit" }
                    output.write(buffer, 0, count)
                }
            }
            val db = open(candidate)
            try {
                val validated = validate(db)
                synchronized(this@OfflineMapStore) {
                    val previous = preferences.getString("active", null)
                    check(preferences.edit().putString("active", candidate.name).commit()) { "Cannot save map selection" }
                    database?.close()
                    database = db; info = validated; cache.evictAll(); adopted = true
                    if (previous != null && previous.matches(Regex("map-[a-f0-9-]+\\.mbtiles"))) File(directory, previous).delete()
                }
                validated
            } finally { if (!adopted) db.close() }
        } finally { if (!adopted) candidate.delete() }
    }

    @Synchronized fun useOverview() {
        val previous = preferences.getString("active", null)
        check(preferences.edit().remove("active").commit()) { "Cannot save map selection" }
        database?.close(); database = null; info = null; cache.evictAll()
        if (previous != null && previous.matches(Regex("map-[a-f0-9-]+\\.mbtiles"))) File(directory, previous).delete()
    }

    @Synchronized fun tiles(zoom: Int, left: Int, top: Int, right: Int, bottom: Int): List<MapTile> {
        val db = database ?: return emptyList()
        val metadata = info ?: return emptyList()
        if (zoom !in metadata.minZoom..metadata.maxZoom) return emptyList()
        require((right - left + 1).toLong() * (bottom - top + 1) <= 128) { "Too many visible tiles" }
        val count = 1 shl zoom
        return buildList {
            for (x in left..right) for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(count - 1)) {
                val wrappedX = ((x % count) + count) % count
                val key = "$zoom/$wrappedX/$y"
                val bitmap = cache[key] ?: db.rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                    arrayOf(zoom.toString(), wrappedX.toString(), MapGeometry.tmsRow(zoom, y).toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else decode(cursor.getBlob(0))?.also { cache.put(key, it) }
                }
                if (bitmap != null) add(MapTile(x, y, zoom, bitmap))
            }
        }
    }

    @Synchronized override fun close() { database?.close(); database = null; cache.evictAll() }

    companion object {
        private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

        internal fun decode(bytes: ByteArray): Bitmap? {
            require(bytes.size <= 8 * 1024 * 1024) { "Map tile is too large" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) { "Invalid or oversized raster tile" }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        internal fun validate(db: SQLiteDatabase): MapInfo {
            val metadata = mutableMapOf<String, String>()
            db.rawQuery("SELECT name, value FROM metadata LIMIT 1000", null).use { c ->
                while (c.moveToNext()) metadata[c.getString(0)] = c.getString(1)
            }
            require(metadata["format"]?.lowercase() in listOf("png", "jpg", "jpeg", "webp")) {
                "Choose raster MBTiles (PNG, JPEG or WebP). Vector PBF maps are not supported."
            }
            require(metadata["scheme"] == null || metadata["scheme"] == "tms") { "Map must use MBTiles TMS rows" }
            val name = metadata["name"]?.takeIf { it.isNotBlank() } ?: error("Map name is missing")
            val range = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
                require(c.moveToFirst() && !c.isNull(0)) { "Map contains no tiles" }
                c.getInt(0)..c.getInt(1)
            }
            require(range.first in 0..22 && range.last in range.first..22) { "Unsupported map zoom levels" }
            db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { c ->
                require(c.moveToFirst() && decode(c.getBlob(0)) != null) { "Map has no readable raster tiles" }
            }
            val attribution = metadata["attribution"].orEmpty()
            require(attribution.length <= 8192) { "Map attribution is too large to display" }
            return MapInfo(name.take(200), Html.fromHtml(attribution, Html.FROM_HTML_MODE_LEGACY)
                .toString(), range.first, range.last)
        }
    }
}
