package com.petpulse.app.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.petpulse.app.data.MediaEntity
import com.petpulse.app.data.PetPulseDatabase
import com.petpulse.core.billing.Entitlements
import com.petpulse.core.billing.Gate
import com.petpulse.core.billing.Plan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Pet photos and videos, stored privately in app storage and shared through a FileProvider. */
class MediaLibrary(private val context: Context, private val db: PetPulseDatabase) {
    private val dir = File(context.filesDir, "media").apply { mkdirs() }

    fun forPet(petId: Long): Flow<List<MediaEntity>> = db.media().observeForPet(petId)
    fun count(): Flow<Int> = db.media().observeCount()
    fun file(m: MediaEntity) = File(dir, m.fileName)

    sealed class AddResult {
        data class Added(val count: Int) : AddResult()
        data class Blocked(val reason: String, val suggested: Plan, val added: Int) : AddResult()
        data class Failed(val message: String, val added: Int) : AddResult()
    }

    /** Copies picked items into app storage, stopping at the plan's limits. */
    suspend fun add(petId: Long, uris: List<Uri>, plan: Plan): AddResult = withContext(Dispatchers.IO) {
        var added = 0
        var total = db.media().count()
        for (uri in uris) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val isVideo = mime.startsWith("video/")
            if (!isVideo && !mime.startsWith("image/")) continue
            val seconds = if (isVideo) durationSeconds(uri) else 0
            when (val gate = Entitlements.canAddMedia(plan, total, isVideo, seconds)) {
                is Gate.NeedsUpgrade -> return@withContext AddResult.Blocked(gate.reason, gate.suggested, added)
                Gate.Allowed -> {}
            }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (isVideo) "mp4" else "jpg"
            val name = "${UUID.randomUUID()}.$ext"
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                return@withContext AddResult.Failed("Couldn't copy that file: ${e.message}", added)
            }
            db.media().insert(MediaEntity(petId = petId, kind = if (isVideo) "VIDEO" else "PHOTO", fileName = name, mimeType = mime,
                durationSec = if (isVideo) seconds else null, caption = null, takenAtMillis = System.currentTimeMillis()))
            added++; total++
        }
        AddResult.Added(added)
    }

    suspend fun delete(m: MediaEntity) = withContext(Dispatchers.IO) {
        db.media().delete(m.id)
        file(m).delete()
    }

    /** Removes files whose rows are gone, e.g. after a pet was deleted (rows go by cascade). */
    suspend fun pruneOrphans() = withContext(Dispatchers.IO) {
        val keep = db.media().allFileNames().toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    /** Opens the system share sheet (Messages, email, WhatsApp, Drive...). */
    fun share(items: List<MediaEntity>, petName: String) {
        if (items.isEmpty()) return
        val uris = items.map { FileProvider.getUriForFile(context, "${context.packageName}.files", file(it)) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(items[0].mimeType)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
        }
        intent.putExtra(Intent.EXTRA_TEXT, "$petName, from CuteFamilyPet")
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share $petName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun durationSeconds(uri: Uri): Int = try {
        MediaMetadataRetriever().run {
            setDataSource(context, uri)
            val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            release()
            ((ms + 999) / 1000).toInt()
        }
    } catch (_: Exception) { 0 }

    /** Small bitmap for grids: a downsampled photo or a video's first frame. */
    suspend fun thumbnail(m: MediaEntity, size: Int = 360): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(m)
        if (!f.exists()) return@withContext null
        if (m.kind == "VIDEO") {
            try {
                MediaMetadataRetriever().run { setDataSource(f.path); val b = getFrameAtTime(0); release(); b?.let { scale(it, size) } }
            } catch (_: Exception) { null }
        } else decodeSampled(f.path, size)
    }

    companion object {
        fun decodeSampled(path: String, size: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        fun scale(b: Bitmap, size: Int): Bitmap {
            val f = size.toFloat() / maxOf(b.width, b.height)
            return if (f >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
        }

        /** JPEG, at most 1568 px on the long side, base64 encoded, for the AI symptom check. */
        fun jpegBase64ForAi(context: Context, uri: Uri): String? = try {
            val tmp = File.createTempFile("ai", ".img", context.cacheDir)
            context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val bmp = decodeSampled(tmp.path, 1568)?.let { scale(it, 1568) }
            tmp.delete()
            bmp?.let {
                val out = ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (_: Exception) { null }
    }
}
