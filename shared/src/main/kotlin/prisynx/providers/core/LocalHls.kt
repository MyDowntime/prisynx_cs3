package prisynx.providers.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiny loopback HTTP server used to expose synthesized HLS masters.
 *
 * CloudStream's player builds its HLS data source from OkHttp/Cronet, so a
 * `data:` master cannot be opened. Serving the same text from 127.0.0.1 keeps
 * the master playable while the video/audio/subtitle URLs inside it stay
 * remote (the player's headers are applied to those requests too).
 */
object LocalHls {
    private data class Entry(val body: ByteArray, val createdAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "prisynx-hls").apply { isDaemon = true }
    }
    private val seq = AtomicInteger(0)

    @Volatile
    private var server: ServerSocket? = null

    /** Register [text] and return a loopback URL, or null if the server cannot start. */
    fun publish(text: String): String? {
        val socket = ensureServer() ?: return null
        cleanup()
        val id = "m${System.currentTimeMillis().toString(36)}${seq.incrementAndGet()}"
        entries[id] = Entry(text.toByteArray(Charsets.UTF_8), System.currentTimeMillis())
        return "http://127.0.0.1:${socket.localPort}/$id.m3u8"
    }

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
        } catch (t: Throwable) {
            null
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 8000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val header = ByteArrayOutputStream()
            while (header.size() <= 8192) {
                val b = input.read()
                if (b < 0) break
                header.write(b)
                val size = header.size()
                if (size >= 4) {
                    val bytes = header.toByteArray()
                    if (bytes[size - 4] == 13.toByte() && bytes[size - 3] == 10.toByte() &&
                        bytes[size - 2] == 13.toByte() && bytes[size - 1] == 10.toByte()
                    ) {
                        break
                    }
                }
            }
            val requestLine = header.toString("ISO-8859-1").lineSequence().firstOrNull().orEmpty()
            val method = requestLine.substringBefore(' ', "").uppercase()
            val path = requestLine.substringAfter(' ', "").substringBefore(' ').substringBefore('?')
            val id = path.trim('/').removeSuffix(".m3u8")
            val entry = entries[id]
            when {
                entry == null -> write(output, "404 Not Found", "text/plain", "not found".toByteArray())
                method == "HEAD" -> writeHead(output, entry.body.size)
                else -> write(output, "200 OK", "application/vnd.apple.mpegurl", entry.body)
            }
            output.flush()
        } catch (t: Throwable) {
            // Client disconnected or malformed request: nothing useful to do.
        } finally {
            try {
                client.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun write(output: OutputStream, status: String, mime: String, body: ByteArray) {
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
    }

    private fun writeHead(output: OutputStream, length: Int) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/vnd.apple.mpegurl\r\n" +
            "Content-Length: $length\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }
}
