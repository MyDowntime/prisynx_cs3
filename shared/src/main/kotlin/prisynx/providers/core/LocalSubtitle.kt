package prisynx.providers.core

import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback HTTP server for decrypted or translated subtitle files.
 */
object LocalSubtitle {
    private const val TAG = "LocalSubtitle"

    private class Entry(
        @Volatile var body: ByteArray?,
        val loader: (suspend () -> String?)?,
        val extension: String,
        val createdAt: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "prisynx-sub").apply { isDaemon = true }
    }
    private val seq = AtomicInteger(0)

    @Volatile
    private var server: ServerSocket? = null

    fun publish(text: String, extension: String = "srt"): String? =
        register(
            Entry(
                body = text.toByteArray(Charsets.UTF_8),
                loader = null,
                extension = normalizeExtension(extension),
                createdAt = System.currentTimeMillis()
            )
        )

    fun publishLazy(extension: String = "srt", loader: suspend () -> String?): String? =
        register(
            Entry(
                body = null,
                loader = loader,
                extension = normalizeExtension(extension),
                createdAt = System.currentTimeMillis()
            )
        )

    private fun register(entry: Entry): String? {
        val socket = ensureServer() ?: return null
        cleanup()
        val id = "s${System.currentTimeMillis().toString(36)}${seq.incrementAndGet()}"
        entries[id] = entry
        return "http://127.0.0.1:${socket.localPort}/$id.${entry.extension}"
    }

    private fun normalizeExtension(value: String): String =
        if (value.trim().lowercase() == "vtt") "vtt" else "srt"

    private fun cleanup() {
        if (entries.size < 256) return
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.createdAt < cutoff) iterator.remove()
        }
        while (entries.size >= 256) {
            val oldest = entries.entries.minByOrNull { it.value.createdAt } ?: break
            entries.remove(oldest.key)
        }
    }

    @Synchronized
    private fun ensureServer(): ServerSocket? {
        server?.let { if (!it.isClosed) return it }
        return try {
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = socket
            pool.execute { acceptLoop(socket) }
            socket
        } catch (e: IOException) {
            Log.e(TAG, "Unable to start subtitle server", e)
            null
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (!socket.isClosed) Log.w(TAG, "Subtitle server stopped", e)
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val header = ByteArrayOutputStream()
            while (header.size() <= 8192) {
                val byte = input.read()
                if (byte < 0) break
                header.write(byte)
                val bytes = header.toByteArray()
                val size = bytes.size
                if (size >= 4 && bytes[size - 4] == 13.toByte() &&
                    bytes[size - 3] == 10.toByte() && bytes[size - 2] == 13.toByte() &&
                    bytes[size - 1] == 10.toByte()
                ) {
                    break
                }
            }

            val requestLine = header.toString("ISO-8859-1").lineSequence().firstOrNull().orEmpty()
            val method = requestLine.substringBefore(' ', "")
            val path = requestLine.substringAfter(' ', "").substringBefore(' ').substringBefore('?')
            val match = Regex("^/([A-Za-z0-9]+)\\.(srt|vtt)$").find(path)
            val entry = match?.let { entries[it.groupValues[1]] }

            when {
                entry == null -> write(output, "404 Not Found", "text/plain", "not found".toByteArray())
                method != "GET" && method != "HEAD" ->
                    write(output, "405 Method Not Allowed", "text/plain", "method not allowed".toByteArray())
                else -> {
                    val body = resolve(entry)
                    when {
                        body == null ->
                            write(output, "502 Bad Gateway", "text/plain", "subtitle unavailable".toByteArray())
                        method == "HEAD" -> writeHead(output, mimeOf(entry.extension), body.size)
                        else -> write(output, "200 OK", mimeOf(entry.extension), body)
                    }
                }
            }
            output.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Subtitle client disconnected", e)
        } finally {
            try {
                client.close()
            } catch (e: IOException) {
                Log.w(TAG, "Unable to close subtitle client", e)
            }
        }
    }

    private fun resolve(entry: Entry): ByteArray? {
        entry.body?.let { return it }
        val loader = entry.loader ?: return null
        synchronized(entry) {
            entry.body?.let { return it }
            val text = try {
                runBlocking { loader() }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to load subtitle", e)
                null
            }
            val bytes = text?.toByteArray(Charsets.UTF_8)
            if (bytes != null) entry.body = bytes
            return bytes
        }
    }

    private fun mimeOf(extension: String): String =
        if (extension == "vtt") "text/vtt; charset=utf-8"
        else "application/x-subrip; charset=utf-8"

    private fun write(output: OutputStream, status: String, mime: String, body: ByteArray) {
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
    }

    private fun writeHead(output: OutputStream, mime: String, length: Int) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: $length\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }
}
