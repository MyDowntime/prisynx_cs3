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

    /** Response produced by a dynamic [Handler]. */
    class Reply(val mime: String, val body: ByteArray)

    /** Serves `/<id>/<path>` requests; return null for 404. Runs on a pool thread, blocking is fine. */
    fun interface Handler {
        fun serve(path: String): Reply?
    }

    private data class Route(val handler: Handler, val createdAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val routes = ConcurrentHashMap<String, Route>()
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

    /** Register [handler] and return its loopback base URL (no trailing slash), or null. */
    fun register(handler: Handler): String? {
        val socket = ensureServer() ?: return null
        if (routes.size >= 512) {
            routes.entries.minByOrNull { it.value.createdAt }?.let { routes.remove(it.key) }
        }
        val id = "h${System.currentTimeMillis().toString(36)}${seq.incrementAndGet()}"
        routes[id] = Route(handler, System.currentTimeMillis())
        return "http://127.0.0.1:${socket.localPort}/$id"
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
            val headerText = header.toString("ISO-8859-1")
            val requestLine = headerText.lineSequence().firstOrNull().orEmpty()
            val method = requestLine.substringBefore(' ', "").uppercase()
            val path = requestLine.substringAfter(' ', "").substringBefore(' ').substringBefore('?')
            val trimmed = path.trim('/')
            val entry = entries[trimmed.removeSuffix(".m3u8")]
            val reply = if (entry != null) {
                Reply("application/vnd.apple.mpegurl", entry.body)
            } else {
                val route = routes[trimmed.substringBefore('/')]
                try {
                    route?.handler?.serve(trimmed.substringAfter('/', ""))
                } catch (t: Throwable) {
                    null
                }
            }
            if (reply == null) {
                write(output, "404 Not Found", "text/plain", "not found".toByteArray())
            } else {
                val range = Regex("(?im)^range:\\s*bytes=(\\d+)-(\\d*)").find(headerText)
                val size = reply.body.size
                val from = range?.groupValues?.get(1)?.toLongOrNull()
                if (from != null && from < size) {
                    val to = (range.groupValues[2].toLongOrNull() ?: (size - 1L)).coerceAtMost(size - 1L)
                    val part = reply.body.copyOfRange(from.toInt(), to.toInt() + 1)
                    val extra = "Content-Range: bytes $from-$to/$size\r\n"
                    if (method == "HEAD") writeHead(output, "206 Partial Content", reply.mime, part.size, extra)
                    else write(output, "206 Partial Content", reply.mime, part, extra)
                } else if (method == "HEAD") {
                    writeHead(output, "200 OK", reply.mime, size)
                } else {
                    write(output, "200 OK", reply.mime, reply.body)
                }
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

    private fun write(output: OutputStream, status: String, mime: String, body: ByteArray, extra: String = "") {
        writeHead(output, status, mime, body.size, extra)
        output.write(body)
    }

    private fun writeHead(output: OutputStream, status: String, mime: String, length: Int, extra: String = "") {
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: $length\r\n" +
            "Accept-Ranges: bytes\r\n" +
            extra +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }
}
