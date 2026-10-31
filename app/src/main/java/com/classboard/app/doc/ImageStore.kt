package com.classboard.app.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * Content-addressed store of image assets shared by every page of a document. Keys are the
 * SHA-1 of the encoded bytes so saving never re-encodes. Decoded bitmaps are cached with a
 * memory bound and in several levels of detail.
 */
class ImageStore {
    private class Entry(val encoded: ByteArray, val suffix: String, val width: Int, val height: Int)

    private val entries = LinkedHashMap<String, Entry>()

    fun addEncoded(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val key = sha1(bytes)
        if (entries.containsKey(key)) return key
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val suffix = when (opts.outMimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "png"
        }
        entries[key] = Entry(bytes, suffix, opts.outWidth, opts.outHeight)
        return key
    }

    fun addBitmap(bmp: Bitmap): String? {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return addEncoded(out.toByteArray())
    }

    fun contains(key: String) = entries.containsKey(key)
    fun keys(): List<String> = entries.keys.toList()
    fun encodedData(key: String): ByteArray? = entries[key]?.encoded
    fun suffix(key: String): String = entries[key]?.suffix ?: "png"
    fun originalSize(key: String): Pair<Int, Int>? = entries[key]?.let { Pair(it.width, it.height) }

    fun retainOnly(used: Set<String>) {
        entries.keys.retainAll(used)
    }

    fun mergeFrom(other: ImageStore) {
        for ((k, v) in other.entries) if (!entries.containsKey(k)) entries[k] = v
    }

    fun clear() = entries.clear()

    /** Bitmap suitable for drawing at the given device pixel size. */
    fun bitmapForSize(key: String, deviceW: Double, deviceH: Double): Bitmap? {
        val e = entries[key] ?: return null
        val fullSide = max(e.width, e.height)
        val wanted = max(deviceW, deviceH)
        var side = fullSide
        while (side / 2 >= wanted && side / 2 >= 64) side /= 2
        side = side.coerceAtMost(MAX_DECODED_SIDE)
        val cacheKey = "$key@$side"
        cache.get(cacheKey)?.let { if (!it.isRecycled) return it }
        var sample = 1
        while (fullSide / (sample * 2) >= side) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(e.encoded, 0, e.encoded.size, opts) ?: return null
        val bs = max(bmp.width, bmp.height)
        if (bs > side * 1.5) {
            val f = side.toDouble() / bs
            bmp = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * f).toInt()), max(1, (bmp.height * f).toInt()), true)
        }
        cache.put(cacheKey, bmp)
        return bmp
    }

    companion object {
        const val MAX_DECODED_SIDE = 3072

        private val cache = object : LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(256L * 1024 * 1024).toInt(),
        ) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        fun sha1(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-1").digest(bytes)
            val sb = StringBuilder()
            for (b in d) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }
}
