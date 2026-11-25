package com.excp.podroid.hostservices

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.excp.podroid.MainActivity
import com.excp.podroid.R
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import dagger.hilt.android.AndroidEntryPoint
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Recording is opt-in per session, never restored by boot or service redelivery. */
@AndroidEntryPoint
class MicrophoneService : Service() {
    @Inject lateinit var engine: VmEngine
    @Inject lateinit var session: MicrophoneSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var stopped = false
    private var started = false
    private var listener: ServerSocket? = null
    private var peer: Socket? = null
    private var recorder: AudioRecord? = null
    @Volatile private var failed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = session.token
        if (intent?.action != START || token == null || engine.state.value !is VmState.Running ||
            engine.stopping.value || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mic_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, MicrophoneService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.mic_enabled))
            .setContentText(getString(R.string.mic_notification))
            .setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.mic_stop), stop).build()
        try {
            ServiceCompat.startForeground(this, 4102, notification,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (_: RuntimeException) {
            failed = true; stopSelf(); return START_NOT_STICKY
        }
        started = true
        session.publish(MicrophonePhase.WAITING)
        scope.launch {
            combine(engine.state, engine.stopping) { state, stopping -> state is VmState.Running && !stopping }
                .collect { if (!it || session.token != token) stopSelf() }
        }
        scope.launch {
            try { serve(token) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; stopSelf() }
        }
        return START_NOT_STICKY
    }

    private suspend fun serve(token: String) {
        while (currentCoroutineContext().isActive) {
            val network = guestNetwork(engine.backendId)
            if (network == null) { delay(1000); continue }
            ServerSocket().use { server ->
                synchronized(lock) {
                    if (stopped) return
                    listener = server
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(network.address, MICROPHONE_PORT), 1)
                    server.soTimeout = 1000
                }
                try {
                    while (currentCoroutineContext().isActive) {
                        try {
                            server.accept().use clientSession@{ client ->
                                synchronized(lock) { if (stopped) return; peer = client }
                                try {
                                    if (!TcpRelay.sameSubnet(network.address, client.inetAddress, network.prefix)) return@clientSession
                                    // Loopback alone cannot authenticate a VM. The per-boot token
                                    // also prevents another Android app from obtaining microphone PCM.
                                    client.soTimeout = 2000
                                    val supplied = ByteArray(65)
                                    var used = 0
                                    while (used < supplied.size) {
                                        val n = client.getInputStream().read(supplied, used, supplied.size - used)
                                        if (n < 0) break
                                        used += n
                                    }
                                    if (used != 65 || !MessageDigest.isEqual(supplied, "$token\n".toByteArray(Charsets.US_ASCII))) return@clientSession
                                    client.soTimeout = 0
                                    client.tcpNoDelay = true
                                    capture(client)
                                } catch (_: java.io.IOException) {
                                    // A disconnected guest does not end the user's enabled session.
                                } finally { synchronized(lock) { peer = null } }
                            }
                        } catch (_: SocketTimeoutException) {
                            if (guestNetwork(engine.backendId) != network) break
                        }
                        if (!stopped) session.publish(MicrophonePhase.WAITING)
                    }
                } finally { synchronized(lock) { listener = null } }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun capture(client: Socket) {
        // Permission is checked before foreground-service startup and again for
        // each new connection; AudioRecord also enforces revocation during use.
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val size = maxOf(3840, AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).build()
        try {
            synchronized(lock) {
                if (stopped) return
                recorder = audio
                audio.startRecording()
                session.publish(MicrophonePhase.RECORDING)
            }
            val bytes = ByteArray(1920)
            while (!stopped) {
                val n = audio.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                check(n > 0) { "AudioRecord failed" }
                client.getOutputStream().write(bytes, 0, n)
            }
        } finally {
            synchronized(lock) { if (recorder === audio) recorder = null; audio.release() }
        }
    }

    override fun onDestroy() {
        synchronized(lock) {
            stopped = true
            runCatching { peer?.close() }
            runCatching { listener?.close() }
            runCatching { recorder?.stop() }
        }
        scope.cancel()
        session.publish(if (failed) MicrophonePhase.FAILED else MicrophonePhase.OFF)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val START = "com.excp.podroid.microphone.START"
        private const val STOP = "com.excp.podroid.microphone.STOP"
        private const val CHANNEL = "podroid_microphone"
        fun start(context: Context) = ContextCompat.startForegroundService(context,
            Intent(context, MicrophoneService::class.java).setAction(START))
        fun stop(context: Context) { context.stopService(Intent(context, MicrophoneService::class.java)) }
    }
}
