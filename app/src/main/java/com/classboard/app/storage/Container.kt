package com.classboard.app.storage

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The versioned `.classboard` container, byte-compatible with the desktop ClassBoard
 * (Qt QDataStream, big endian): magic, version, entries of (name, flags, crc32, data).
 */
class Container {
    class Entry(val name: String, val data: ByteArray, val compress: Boolean)

    val entries = ArrayList<Entry>()

    fun add(name: String, data: ByteArray, compress: Boolean) = entries.add(Entry(name, data, compress))
    fun find(name: String) = entries.firstOrNull { it.name == name }

    fun write(): ByteArray {
        val bos = ByteArrayOutputStream()
        val s = DataOutputStream(bos)
        s.write(MAGIC)
        s.writeInt(VERSION)
        s.writeInt(entries.size)
        for (e in entries) {
            writeQString(s, e.name)
            s.writeByte(if (e.compress) 1 else 0)
            s.writeInt(crc32(e.data).toInt())
            writeQByteArray(s, if (e.compress) qCompress(e.data) else e.data)
        }
        s.flush()
        return bos.toByteArray()
    }

    class FormatException(msg: String) : Exception(msg)

    fun read(bytes: ByteArray) {
        entries.clear()
        if (!looksLikeContainer(bytes)) throw FormatException("This is not a ClassBoard lesson file.")
        val s = DataInputStream(bytes.inputStream())
        try {
            s.skipBytes(8)
            val version = s.readInt()
            val count = s.readInt().toLong() and 0xFFFFFFFFL
            if (version > VERSION) throw FormatException("The lesson file was created by a newer version of ClassBoard.")
            if (count > 1000000) throw FormatException("The lesson file is corrupted.")
            for (i in 0 until count) {
                val name = readQString(s)
                val flags = s.readUnsignedByte()
                val crc = s.readInt().toLong() and 0xFFFFFFFFL
                val raw = readQByteArray(s)
                val compress = flags and 1 != 0
                val data = if (compress) qUncompress(raw) ?: throw FormatException("Part '$name' of the lesson file is corrupted.") else raw
                if (crc32(data) != crc) throw FormatException("Part '$name' of the lesson file is corrupted.")
                entries.add(Entry(name, data, compress))
            }
        } catch (e: EOFException) {
            entries.clear()
            throw FormatException("The lesson file is truncated.")
        }
    }

    companion object {
        val MAGIC = "CLASSBRD".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1

        fun looksLikeContainer(head: ByteArray) = head.size >= 8 && (0 until 8).all { head[it] == MAGIC[it] }

        fun crc32(data: ByteArray): Long = CRC32().also { it.update(data) }.value

        private fun writeQString(s: DataOutputStream, str: String) {
            val b = str.toByteArray(Charsets.UTF_16BE)
            s.writeInt(b.size)
            s.write(b)
        }

        private fun readQString(s: DataInputStream): String {
            val len = s.readInt()
            if (len == -1) return ""
            if (len < 0 || len > 64 * 1024 * 1024) throw FormatException("The lesson file is corrupted.")
            val b = ByteArray(len)
            s.readFully(b)
            return String(b, Charsets.UTF_16BE)
        }

        private fun writeQByteArray(s: DataOutputStream, b: ByteArray) {
            s.writeInt(b.size)
            s.write(b)
        }

        private fun readQByteArray(s: DataInputStream): ByteArray {
            val len = s.readInt()
            if (len == -1) return ByteArray(0)
            if (len < 0) throw FormatException("The lesson file is corrupted.")
            val b = ByteArray(len)
            s.readFully(b)
            return b
        }

        /** Qt qCompress: 4-byte big-endian uncompressed size followed by a zlib stream. */
        fun qCompress(data: ByteArray, level: Int = 6): ByteArray {
            val d = Deflater(level)
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val n = data.size
            out.write((n ushr 24) and 0xFF); out.write((n ushr 16) and 0xFF); out.write((n ushr 8) and 0xFF); out.write(n and 0xFF)
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) {
                val k = d.deflate(buf)
                out.write(buf, 0, k)
            }
            d.end()
            return out.toByteArray()
        }

        fun qUncompress(data: ByteArray): ByteArray? {
            if (data.size < 4) return if (data.isEmpty()) ByteArray(0) else null
            val expected = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val inf = Inflater()
            inf.setInput(data, 4, data.size - 4)
            val out = ByteArrayOutputStream(if (expected in 1..(512 * 1024 * 1024)) expected else 1024)
            val buf = ByteArray(64 * 1024)
            return try {
                while (!inf.finished()) {
                    val k = inf.inflate(buf)
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, k)
                }
                out.toByteArray()
            } catch (e: DataFormatException) {
                null
            } finally {
                inf.end()
            }
        }
    }
}
