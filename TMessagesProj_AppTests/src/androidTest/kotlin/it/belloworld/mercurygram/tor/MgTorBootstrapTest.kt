package it.belloworld.mercurygram.tor

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MgTorBootstrapTest {

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread

    @Before
    fun setUp() {
        server = ServerSocket(0)
    }

    @After
    fun tearDown() {
        try { server.close() } catch (_: IOException) {}
        if (::serverThread.isInitialized) serverThread.join(2_000)
    }

    @Test
    fun parsesBootstrapProgressAndReady() {
        // Fake tor control port: ACK auth, ACK setevents, reply to the GETINFO
        // status/bootstrap-phase probe with PROGRESS=0 (cold start — bootstrap
        // hasn't begun), then stream the progress events 5 -> 50 -> 100.
        serveControlPort(
            authReply = "250 OK",
            seteventsReply = "250 OK",
            getInfoReply = listOf(
                "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=0 TAG=starting SUMMARY=\"Starting\"",
                "250 OK"
            ),
            asyncLines = listOf(
                "650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=5 TAG=conn_dir SUMMARY=\"Connecting to a relay directory\"",
                "650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=50 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\"",
                "650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""
            )
        )

        val progressCount = AtomicInteger()
        val lastProgress = AtomicInteger()
        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>()

        val boot = MgTorBootstrap(server.localPort, null, object : MgTorBootstrap.Listener {
            override fun onBootstrapProgress(percent: Int, tag: String?, summary: String?) {
                progressCount.incrementAndGet()
                lastProgress.set(percent)
            }
            override fun onBootstrapReady() { ready.countDown() }
            override fun onBootstrapFailed(reason: String?) { failure.set(reason) }
        })

        val bootThread = Thread(boot, "bootstrap-test")
        bootThread.start()
        assertTrue("did not reach 100% in time", ready.await(5, TimeUnit.SECONDS))
        bootThread.join(2_000)

        assertEquals(null, failure.get())
        assertEquals(3, progressCount.get())
        assertEquals(100, lastProgress.get())
    }

    @Test
    fun warmStartReadyFromGetInfo() {
        // Warm start: tor has already crossed PROGRESS=100 before SETEVENTS.
        // STATUS_CLIENT events would never re-fire. The GETINFO probe must
        // pick up the cached 100 and short-circuit onBootstrapReady.
        serveControlPort(
            authReply = "250 OK",
            seteventsReply = "250 OK",
            getInfoReply = listOf(
                "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"",
                "250 OK"
            ),
            asyncLines = emptyList()
        )

        val ready = CountDownLatch(1)
        val progressCount = AtomicInteger()
        val failure = AtomicReference<String?>()

        val boot = MgTorBootstrap(server.localPort, null, object : MgTorBootstrap.Listener {
            override fun onBootstrapProgress(percent: Int, tag: String?, summary: String?) {
                progressCount.incrementAndGet()
            }
            override fun onBootstrapReady() { ready.countDown() }
            override fun onBootstrapFailed(reason: String?) { failure.set(reason) }
        })

        val t = Thread(boot, "bootstrap-test-warm")
        t.start()
        assertTrue("warm-start GETINFO did not short-circuit ready", ready.await(5, TimeUnit.SECONDS))
        t.join(2_000)
        assertEquals(null, failure.get())
        assertEquals(0, progressCount.get())
    }

    @Test
    fun failsOnAuthRejection() {
        serveControlPort(
            authReply = "515 Authentication failed",
            seteventsReply = null,
            getInfoReply = emptyList(),
            asyncLines = emptyList()
        )

        val failure = AtomicReference<String?>()
        val done = CountDownLatch(1)
        val boot = MgTorBootstrap(server.localPort, null, object : MgTorBootstrap.Listener {
            override fun onBootstrapProgress(percent: Int, tag: String?, summary: String?) {}
            override fun onBootstrapReady() {}
            override fun onBootstrapFailed(reason: String?) {
                failure.set(reason)
                done.countDown()
            }
        })
        Thread(boot, "bootstrap-test-auth-fail").start()
        assertTrue("did not fail in time", done.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get()?.startsWith("auth: 515") == true)
    }

    @Test
    fun cookieAppearsLateWithinExtendedBudget() {
        // tor writes the 32-byte cookie ~7 s after the control port binds on
        // this run (memory-pressured device simulation). The previous 5 s
        // budget would have returned an empty cookie → bare AUTHENTICATE →
        // 515 rejection. With the 15 s budget bumped in MgTorBootstrap, the
        // poll loop must hold long enough to read the cookie and send the
        // hex-encoded AUTHENTICATE that the (fake) server accepts.
        serveControlPort(
            authReply = "250 OK",
            seteventsReply = "250 OK",
            getInfoReply = listOf(
                "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"",
                "250 OK"
            ),
            asyncLines = emptyList(),
            expectHexCookie = true
        )

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cookieFile = File(ctx.cacheDir, "mg-tor-test-cookie-${System.nanoTime()}")
        cookieFile.delete()

        // Async-write the 32-byte cookie 7 s after bootstrap starts polling.
        val cookieWriter = Thread {
            try { Thread.sleep(7_000) } catch (_: InterruptedException) { return@Thread }
            try {
                FileOutputStream(cookieFile).use { it.write(ByteArray(32) { i -> i.toByte() }) }
            } catch (_: IOException) {}
        }
        cookieWriter.isDaemon = true

        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val boot = MgTorBootstrap(server.localPort, cookieFile, object : MgTorBootstrap.Listener {
            override fun onBootstrapProgress(percent: Int, tag: String?, summary: String?) {}
            override fun onBootstrapReady() { ready.countDown() }
            override fun onBootstrapFailed(reason: String?) { failure.set(reason) }
        })

        cookieWriter.start()
        val t = Thread(boot, "bootstrap-test-cookie-late")
        t.start()
        // Old 5 s budget would have failed by ~5.1 s. New 15 s budget lets
        // the 7 s-deferred cookie arrive. Allow 12 s headroom.
        assertTrue("bootstrap did not complete within extended cookie budget",
            ready.await(12, TimeUnit.SECONDS))
        t.join(2_000)
        cookieFile.delete()
        assertEquals(null, failure.get())
    }

    @Test
    fun cancelStopsParser() {
        // Server ACKs auth + setevents + GETINFO (with PROGRESS=0 so no
        // ready short-circuit) then stays silent. cancel() must close the
        // socket and bootstrap returns with no further callback.
        serveControlPort(
            authReply = "250 OK",
            seteventsReply = "250 OK",
            getInfoReply = listOf(
                "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=0 TAG=starting SUMMARY=\"Starting\"",
                "250 OK"
            ),
            asyncLines = emptyList()
        )
        val sawCallback = AtomicReference<String?>()
        val boot = MgTorBootstrap(server.localPort, null, object : MgTorBootstrap.Listener {
            override fun onBootstrapProgress(percent: Int, tag: String?, summary: String?) {
                sawCallback.set("progress")
            }
            override fun onBootstrapReady() { sawCallback.set("ready") }
            override fun onBootstrapFailed(reason: String?) { sawCallback.set("failed:$reason") }
        })
        val t = Thread(boot, "bootstrap-test-cancel")
        t.start()
        Thread.sleep(300)
        boot.cancel()
        t.join(2_000)
        assertEquals(null, sawCallback.get())
    }

    private fun serveControlPort(
        authReply: String,
        seteventsReply: String?,
        getInfoReply: List<String>,
        asyncLines: List<String>,
        expectHexCookie: Boolean = false
    ) {
        serverThread = Thread {
            try {
                val client: Socket = server.accept()
                client.use { sock ->
                    val reader = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val w = sock.getOutputStream()
                    val authLine = reader.readLine() // AUTHENTICATE [hex]
                    // When the test needs to confirm the bootstrap budget was
                    // long enough to read a cookie, reject bare AUTHENTICATE
                    // (no hex arg) so an empty-cookie path fails fast.
                    val authedWithHex = authLine != null
                        && authLine.startsWith("AUTHENTICATE ")
                        && authLine.length > "AUTHENTICATE ".length
                    val effectiveReply = if (expectHexCookie && !authedWithHex) {
                        "515 Authentication required: cookie missing"
                    } else {
                        authReply
                    }
                    w.write((effectiveReply + "\r\n").toByteArray(StandardCharsets.UTF_8))
                    w.flush()
                    if (expectHexCookie && !authedWithHex) {
                        return@use
                    }
                    if (seteventsReply != null) {
                        reader.readLine() // SETEVENTS STATUS_CLIENT
                        w.write((seteventsReply + "\r\n").toByteArray(StandardCharsets.UTF_8))
                        w.flush()
                        if (getInfoReply.isNotEmpty()) {
                            reader.readLine() // GETINFO status/bootstrap-phase
                            for (line in getInfoReply) {
                                w.write((line + "\r\n").toByteArray(StandardCharsets.UTF_8))
                                w.flush()
                            }
                        }
                        for (line in asyncLines) {
                            w.write((line + "\r\n").toByteArray(StandardCharsets.UTF_8))
                            w.flush()
                            Thread.sleep(20)
                        }
                    }
                    // Hold the socket open until the client closes or the
                    // test tear-down closes the server.
                    Thread.sleep(2_000)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
    }
}
