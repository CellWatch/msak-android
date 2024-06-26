package edu.gatech.cc.cellwatch.msak.latency

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import edu.gatech.cc.cellwatch.msak.LATENCY_CHARSET
import edu.gatech.cc.cellwatch.msak.LATENCY_DURATION
import edu.gatech.cc.cellwatch.msak.Log
import edu.gatech.cc.cellwatch.msak.Server
import io.ktor.http.Url
import io.ktor.util.reflect.instanceOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A latency test.
 *
 * @param server The Server against which to run the test, typically obtained via a LocateManager.
 * @param client An optional OkHttpClient to use for the test. If not provided, a new one will be
 *               created.
 * @param measurementId A unique ID for the measurement. Typically not needed if the server was
 *                      obtained via a LocateManager.
 * @param latencyPort The port on which the measurement server is listening for the latency test's
 *                    UDP packets.
 * @param duration The duration in milliseconds for which the test will run. Should not be provided
 *                 if using M-Lab's server infrastructure.
 * @param retryDelay How long, in milliseconds, to wait for a response to the initial packet before
 *                   resending it.
 * @param retryBackoff How much longer, in milliseconds, to wait each time the initial packet must
 *                     be resent.
 * @param userAgent The value to use in the User-Agent header for HTTP requests to initiate the
 *                  test and obtain the results.
 */
class LatencyTest(
    private val server: Server,
    client: OkHttpClient? = null,
    measurementId: String? = null,
    private val latencyPort: Int = 1053,
    private val duration: Long = LATENCY_DURATION,
    private val retryDelay: Long = 1000L,
    private val retryBackoff: Long = 500L,
    private val userAgent: String? = null,
) {
    private val TAG = this::class.simpleName
    private val authorizeUrl = server.getLatencyAuthorizeUrl(measurementId)
    private val resultUrl = server.getLatencyResultUrl(measurementId)
    private val _updatesChan = Channel<LatencyUpdate>(32)
    private val _updates = ArrayList<LatencyUpdate>()
    private val client = client ?: OkHttpClient.Builder().build()
    private val socket = DatagramSocket()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * A channel on which to receive updates as the latency test progresses. Will be closed when the
     * test is complete.
     */
    val updatesChan: ReceiveChannel<LatencyUpdate> = _updatesChan

    /**
     * All updates received during the test so far.
     */
    val updates: List<LatencyUpdate> = _updates

    /**
     * The start time of the test.
     */
    var startTime: Instant? = null; private set

    /**
     * The end time of the test. To wait for the test to end, use updatesChan.
     */
    var endTime: Instant? = null; private set

    /**
     * Whether the test has been started.
     */
    var started = false; private set

    /**
     * Whether the test has ended. To wait for the test to end, use updatesChan.
     */
    val ended; get() = endTime != null

    /**
     * The hostname of the server against which the test runs. Note that this may differ from the
     * machine value provided by the Server.
     */
    val serverHost = Url(authorizeUrl).host

    /**
     * The result of the latency test. Will not be set until the test is complete and updatesChan is
     * closed. May be null if error is not null.
     */
    var result: LatencyResult? = null

    /**
     * The error that caused the latency test to end, if applicable. Will not be set until the test
     * is complete and updatesChan is closed.
     */
    var error: Throwable? = null

    /**
     * Begin the latency test. Monitor its updates and wait for it to end with updatesChan.
     */
    fun start() {
        if (started) {
            throw Exception("already started")
        }

        started = true
        thread {
            try {
                runBlocking { run() }
            } catch (e: Throwable) {
                Log.i(TAG, "latency test error", e)
                error = e
            } finally {
                finish()
                _updatesChan.close()
            }
        }
    }

    /**
     * End the latency test. The test will end on its own when complete, but this method can be used
     * to abort it early.
     */
    fun stop() {
        if (!started) {
            throw Exception("can't stop before starting")
        }

        finish()
    }

    private suspend fun run() {
        val initialMessage = authorize()
        Log.d(TAG, "got initial latency message: $initialMessage")

        val serverAddr = getServerAddr()
        Log.d(TAG, "using latency address $serverAddr for ${server.machine}")

        echoPackets(serverAddr, initialMessage)

        result = getResult()
        Log.d(TAG, "got latency result: $result")
    }

    private fun finish(closeUpdatesChan: Boolean = true) {
        if (ended) {
            return
        }

        endTime = Clock.System.now()
        handler.removeCallbacksAndMessages(null)
        socket.close()
        if (closeUpdatesChan) {
            _updatesChan.close()
        }
    }

    private fun recordUpdate(update: LatencyUpdate) {
        val result = _updatesChan.trySend(update)
        if (!result.isSuccess) {
            Log.d(TAG, "failed to send latency message on channel: $result")
        }

        if (!result.isClosed) {
            _updates.add(update)
        }
    }

    private suspend fun authorize(): LatencyMessage = suspendCoroutine { continuation ->
        Log.d(TAG, "making authorize request to $authorizeUrl")
        val builder = Request.Builder().url(authorizeUrl)
        if (userAgent != null) {
            builder.header("User-Agent", userAgent)
        }
        val request = builder.build()

        client.newCall(request).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "authorize request failure: $call", e)
                continuation.resumeWithException(AuthorizeFailureExecption())
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body
                if (response.code != 200 || body == null) {
                    Log.i(TAG, "authorize request $request failed: $response")
                    continuation.resumeWithException(UnauthorizedException())
                    return
                }

                val initialMessage = try {
                    Gson().fromJson(body.charStream(), LatencyMessage::class.java)
                } catch (e: JsonSyntaxException) {
                    Log.e(TAG, "authorize response deserialization failed: $body", e)
                    continuation.resumeWithException(e)
                    return
                }

                continuation.resume(initialMessage)
            }
        })
    }

   private fun getServerAddr(): InetAddress {
       try {
           val addrs = InetAddress.getAllByName(serverHost)
           Log.d(TAG, "got latency addrs ${addrs.joinToString(", ")}")
           val v4Addrs = addrs.filter { it.instanceOf(Inet4Address::class) }
           // prefer IPv4 as IPv6 connectivity is often incomplete
           return if (v4Addrs.isNotEmpty()) v4Addrs[0] else addrs[0]
       } catch (t: Throwable) {
           Log.i(TAG, "no server addr for latency test", t)
           throw NoAddrException()
       }
   }

    private fun echoPackets(serverAddr: InetAddress, initialMessage: LatencyMessage) {
        val initialBuf = ByteArray(1024)
        val initialMessageBytes = Gson().toJson(initialMessage).toByteArray(LATENCY_CHARSET)
        initialMessageBytes.forEachIndexed { i, b -> initialBuf[i] = b }

        val initialPkt = DatagramPacket(initialBuf, initialMessageBytes.size)
        initialPkt.address = serverAddr
        initialPkt.port = latencyPort

        var gotOne = false
        thread {
            startTime = Clock.System.now()
            val maxAttempts = 3
            var attemptsRemaining = maxAttempts
            while (!gotOne && attemptsRemaining > 0) {
                Log.d(TAG, "sending initial packet; ${attemptsRemaining - 1} attempt(s) remaining")
                socket.send(initialPkt)
                Thread.sleep(retryDelay + retryBackoff * (maxAttempts - attemptsRemaining))
                attemptsRemaining--
            }

            if (!gotOne) {
                Log.i(TAG, "never received next latency packet")
                error = InitialPacketTimeoutException()
                finish(false)
            }
        }

        val buf = ByteArray(1024)
        val pkt = DatagramPacket(buf, buf.size)
        while (true) {
            pkt.length = buf.size
            try {
                socket.receive(pkt)
            } catch (t: Throwable) {
                if (ended) {
                    break
                }

                Log.e(TAG, "failed to receive packet", t)
                throw t
            }
            val time = Clock.System.now()

            if (pkt.address != serverAddr || pkt.port != latencyPort) {
                Log.w(TAG, "received packet from unexpected sender: ${pkt.address}:${pkt.port}")
                continue
            }

            if (!gotOne) {
                gotOne = true
                handler.postDelayed({ finish(false) }, duration + 1000L)
            }

            val payload = buf.sliceArray(IntRange(0, pkt.length - 1)).toString(LATENCY_CHARSET)
            Log.v(TAG, "received packet: $payload")

            try {
                socket.send(pkt)
            } catch (t: Throwable) {
                if (ended) {
                    break
                }

                Log.e(TAG, "failed to send packet")
                throw t
            }

            val message = try {
                Gson().fromJson(payload, LatencyMessage::class.java)
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "latency message deserialization failed: $payload", e)
                continue
            }

            recordUpdate(LatencyUpdate(time, message))
        }
    }

    private suspend fun getResult(): LatencyResult = suspendCoroutine { continuation ->
        val request = Request.Builder().url(resultUrl).build()

        client.newCall(request).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "results request failure: $call", e)
                continuation.resumeWithException(ResultFailureException())
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body
                if (response.code != 200 || body == null) {
                    Log.i(TAG, "results request $request failed: $response")
                    continuation.resumeWithException(NoResultException())
                    return
                }

                val bodyStr = body.string()
                val result = try {
                    Gson().fromJson(bodyStr, LatencyResult::class.java)
                } catch (e: JsonSyntaxException) {
                    Log.e(TAG, "results response deserialization failed: $bodyStr", e)
                    continuation.resumeWithException(e)
                    return
                }

                continuation.resume(result)
            }
        })
    }

    class AuthorizeFailureExecption: Exception("authorize call failed")
    class UnauthorizedException: Exception("authorize call returned bad response")
    class ResultFailureException: Exception("result call failed")
    class NoResultException: Exception("result call returned bad response")
    class InitialPacketTimeoutException: Exception("initial packet timeout")
    class NoAddrException: Exception("could not resolve server addr")
}
