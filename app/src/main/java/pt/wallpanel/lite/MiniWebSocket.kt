package pt.wallpanel.lite

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Não chegou nada durante o tempo de leitura (a ligação pode estar só parada). */
class IdleTimeoutException : IOException("sem dados")

/**
 * Cliente WebSocket (RFC 6455) mínimo, sem dependências: ws:// e wss://, mensagens de texto,
 * ping/pong e fecho. Chega para falar com a API WebSocket do Home Assistant.
 */
class MiniWebSocket(
    private val url: String,
    private val trustAll: Boolean = false,
    private val readTimeoutMs: Int = 25_000,
) {
    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MAX_PAYLOAD = 8L * 1024 * 1024
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    private val random = SecureRandom()

    fun connect(connectTimeoutMs: Int = 8000) {
        val uri = URI(url)
        val tls = uri.scheme.equals("wss", ignoreCase = true)
        val host = uri.host ?: throw IOException("URL inválido")
        val port = if (uri.port != -1) uri.port else if (tls) 443 else 80
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        raw.soTimeout = connectTimeoutMs
        raw.tcpNoDelay = true
        val s: Socket = if (tls) {
            val factory: SSLSocketFactory = if (trustAll) trustAllContext().socketFactory
            else SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            if (!trustAll) {
                val p = ssl.sslParameters
                p.endpointIdentificationAlgorithm = "HTTPS" // valida o nome do servidor
                ssl.sslParameters = p
            }
            ssl.startHandshake()
            ssl
        } else raw
        socket = s
        input = DataInputStream(BufferedInputStream(s.getInputStream()))
        output = s.getOutputStream()

        val keyBytes = ByteArray(16).also { random.nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val hostHeader = if (uri.port != -1) "$host:$port" else host
        val request = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        synchronized(writeLock) { output!!.write(request.toByteArray(Charsets.ISO_8859_1)); output!!.flush() }

        val status = readHttpLine()
        if (!status.contains(" 101")) throw IOException("handshake recusado: $status")
        var accept: String? = null
        while (true) {
            val line = readHttpLine()
            if (line.isEmpty()) break
            if (line.startsWith("Sec-WebSocket-Accept:", ignoreCase = true)) accept = line.substringAfter(':').trim()
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1))
        )
        if (accept != expected) throw IOException("handshake inválido")
        s.soTimeout = readTimeoutMs
    }

    fun send(text: String) = writeFrame(0x1, text.toByteArray(Charsets.UTF_8))

    /** Próxima mensagem de texto; null se o servidor fechou. Lança [IdleTimeoutException] se nada chegar a tempo. */
    fun receive(): String? {
        val inp = input ?: throw IOException("fechado")
        val buf = ByteArrayOutputStream()
        var inMessage = false
        while (true) {
            val b0 = try { inp.readUnsignedByte() } catch (e: SocketTimeoutException) {
                // Só é "inatividade" se não estamos a meio de uma mensagem (senão o stream ficava desalinhado).
                if (inMessage) throw IOException("tempo esgotado a meio de uma mensagem") else throw IdleTimeoutException()
            }
            val b1 = inp.readUnsignedByte()
            val fin = (b0 and 0x80) != 0
            val op = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
            if (len < 0 || len > MAX_PAYLOAD) throw IOException("mensagem demasiado grande")
            val mask = if ((b1 and 0x80) != 0) ByteArray(4).also { inp.readFully(it) } else null
            val payload = ByteArray(len.toInt())
            inp.readFully(payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (op) {
                0x8 -> return null
                0x9 -> writeFrame(0xA, payload)
                0xA -> {}
                0x1, 0x2 -> { buf.reset(); buf.write(payload); inMessage = !fin }
                0x0 -> { buf.write(payload); inMessage = !fin }
                else -> throw IOException("opcode inesperado $op")
            }
            if ((op == 0x1 || op == 0x2 || op == 0x0) && fin) return buf.toString("UTF-8")
        }
    }

    fun close() {
        try { writeFrame(0x8, ByteArray(0)) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun writeFrame(opcode: Int, data: ByteArray) {
        val out = output ?: throw IOException("fechado")
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            data.size < 126 -> header.write(0x80 or data.size)
            data.size < 65536 -> { header.write(0x80 or 126); header.write(data.size shr 8); header.write(data.size and 0xFF) }
            else -> {
                header.write(0x80 or 127)
                for (i in 7 downTo 0) header.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val body = ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(writeLock) { out.write(header.toByteArray()); out.write(body); out.flush() }
    }

    private fun readHttpLine(): String {
        val inp = input!!
        val sb = StringBuilder()
        while (true) {
            val c = inp.readUnsignedByte()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) throw IOException("cabeçalho demasiado grande")
        }
        return sb.toString()
    }

    private fun trustAllContext(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
    }
}
