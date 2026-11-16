package com.example.kerjacam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Space(val id: String, val name: String)
data class Album(val id: String, val spaceId: String, val name: String, val description: String)
data class Item(
    val id: String,
    val spaceId: String,
    val albumId: String?, // null = Belum dikategorikan
    val fileName: String,
    val createdAt: Long,
    val note: String
)

/** Semua data disimpan lokal di folder privat aplikasi (tidak masuk galeri perangkat). */
class Repo(private val ctx: Context) {
    val spaces = mutableStateListOf<Space>()
    val albums = mutableStateListOf<Album>()
    val items = mutableStateListOf<Item>()

    private val dbFile = File(ctx.filesDir, "db.json")
    val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }

    init { load() }

    private fun uid() = UUID.randomUUID().toString()
    fun fileOf(item: Item) = File(mediaDir, item.fileName)

    private fun load() {
        if (!dbFile.exists()) return
        runCatching {
            val o = JSONObject(dbFile.readText())
            o.getJSONArray("spaces").let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    spaces += Space(it.getString("id"), it.getString("name"))
                }
            }
            o.getJSONArray("albums").let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    albums += Album(it.getString("id"), it.getString("spaceId"), it.getString("name"), it.optString("description"))
                }
            }
            o.getJSONArray("items").let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    items += Item(
                        it.getString("id"), it.getString("spaceId"),
                        it.optString("albumId").ifEmpty { null },
                        it.getString("fileName"), it.getLong("createdAt"), it.optString("note")
                    )
                }
            }
        }
    }

    private fun save() {
        val o = JSONObject()
        o.put("spaces", JSONArray().also { a -> spaces.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } })
        o.put("albums", JSONArray().also { a ->
            albums.forEach {
                a.put(JSONObject().put("id", it.id).put("spaceId", it.spaceId).put("name", it.name).put("description", it.description))
            }
        })
        o.put("items", JSONArray().also { a ->
            items.forEach {
                a.put(
                    JSONObject().put("id", it.id).put("spaceId", it.spaceId).put("albumId", it.albumId ?: "")
                        .put("fileName", it.fileName).put("createdAt", it.createdAt).put("note", it.note)
                )
            }
        })
        dbFile.writeText(o.toString())
    }

    // ---- Ruang ----
    fun addSpace(name: String) { spaces += Space(uid(), name.trim()); save() }

    fun deleteSpace(id: String) {
        items.filter { it.spaceId == id }.forEach { fileOf(it).delete() }
        items.removeAll { it.spaceId == id }
        albums.removeAll { it.spaceId == id }
        spaces.removeAll { it.id == id }
        save()
    }

    // ---- Koleksi ----
    fun addAlbum(spaceId: String, name: String, description: String = ""): String {
        val a = Album(uid(), spaceId, name.trim(), description)
        albums += a; save()
        return a.id
    }

    /** Foto dalam koleksi yang dihapus dipindah ke "Belum dikategorikan". */
    fun deleteAlbum(id: String) {
        for (i in items.indices) if (items[i].albumId == id) items[i] = items[i].copy(albumId = null)
        albums.removeAll { it.id == id }
        save()
    }

    // ---- Foto ----
    fun addItem(spaceId: String, albumId: String?, fileName: String) {
        items += Item(uid(), spaceId, albumId, fileName, System.currentTimeMillis(), "")
        save()
    }

    fun updateNote(id: String, note: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) { items[i] = items[i].copy(note = note); save() }
    }

    fun deleteItem(id: String) {
        items.firstOrNull { it.id == id }?.let { fileOf(it).delete() }
        items.removeAll { it.id == id }
        save()
    }

    /** Salin gambar dari galeri/aplikasi lain ke penyimpanan privat. Jalankan di thread IO. */
    fun copyIn(uri: Uri): String? = runCatching {
        val name = "${uid()}.jpg"
        ctx.contentResolver.openInputStream(uri)!!.use { i -> File(mediaDir, name).outputStream().use { i.copyTo(it) } }
        name
    }.getOrNull()

    /** Ekspor satu foto ke galeri perangkat (Pictures/KerjaCam/<koleksi>). */
    fun exportToGallery(item: Item, folder: String): Boolean = runCatching {
        val safe = folder.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "KerjaCam_${item.createdAt}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KerjaCam/$safe")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { out -> fileOf(item).inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)
}
