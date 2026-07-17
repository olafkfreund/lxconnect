package com.lxconnect.mcp

import android.util.Log
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * TLS terminator using Android's own (Conscrypt) SSLServerSocket — the platform TLS
 * stack every app relies on, so it's reliable. Ktor Netty's SslHandler wrapping the
 * Conscrypt SSLEngine throws java.lang.AssertionError on this hardware, so instead the
 * Ktor server runs plaintext on localhost and this proxy terminates TLS on [listenPort]
 * and pipes the decrypted bytes to it. Serves the same keystore cert, so the daemon's
 * fingerprint pin is unchanged.
 */
class TlsProxy(
    keyStore: KeyStore,
    password: CharArray,
    private val listenPort: Int = 8080,
    private val targetPort: Int = 8081,
) : Closeable {

    private val serverSocket: SSLServerSocket
    @Volatile private var running = true

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        serverSocket = (ctx.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", listenPort))
        }
    }

    fun start() {
        thread(name = "lxconnect-tls-proxy", isDaemon = true) {
            Log.i(TAG, "TLS proxy listening on $listenPort -> 127.0.0.1:$targetPort")
            while (running) {
                try {
                    handle(serverSocket.accept())
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "accept failed", e)
                }
            }
        }
    }

    private fun handle(client: Socket) {
        val upstream = try {
            Socket("127.0.0.1", targetPort)
        } catch (e: Exception) {
            Log.e(TAG, "upstream connect failed", e)
            try { client.close() } catch (_: Exception) {}
            return
        }
        pipe(client, upstream) // request bytes
        pipe(upstream, client) // response / SSE stream
    }

    // One-way copy on its own daemon thread; closing either end tears down both.
    private fun pipe(from: Socket, to: Socket) {
        thread(isDaemon = true) {
            try {
                from.getInputStream().copyTo(to.getOutputStream(), 8192)
            } catch (_: Exception) {
            } finally {
                try { from.close() } catch (_: Exception) {}
                try { to.close() } catch (_: Exception) {}
            }
        }
    }

    override fun close() {
        running = false
        try { serverSocket.close() } catch (_: Exception) {}
    }

    companion object { private const val TAG = "LxTlsProxy" }
}
