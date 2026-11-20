package com.timtam.clapfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Listens for claps in the background and sets off the alarm. */
class ClapService : Service() {

    companion object {
        const val ACTION_START = "com.timtam.clapfinder.START"
        const val ACTION_STOP = "com.timtam.clapfinder.STOP"
        const val ACTION_FOUND = "com.timtam.clapfinder.FOUND"
        private const val CH_LISTEN = "listening"
        private const val CH_ALARM = "alarm"
        private const val NOTE_ID = 7

        @Volatile var listening = false
        @Volatile var alarming = false
        @Volatile var level = 0f
        @Volatile var lastClapAt = 0L
        @Volatile var clapsInRow = 0
        @Volatile var error: String? = null

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ClapService::class.java).setAction(action)
            try {
                if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                error = "Android wouldn't let Clap Finder start. Open the app and try again."
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var alarm: AlarmPlayer
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var keepGoing = false
    @Volatile private var muteUntil = 0L
    private var wake: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var torchOn = false

    private val autoStop = Runnable { stopAlarm() }
    private val torchBlink = object : Runnable {
        override fun run() {
            if (!alarming) {
                setTorch(false)
                return
            }
            setTorch(!torchOn)
            main.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        alarm = AlarmPlayer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Listening for claps", NotificationManager.IMPORTANCE_LOW))
        val alarmChannel = NotificationChannel(CH_ALARM, "Phone found alarm", NotificationManager.IMPORTANCE_HIGH)
        alarmChannel.setSound(null, null)
        nm.createNotificationChannel(alarmChannel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarm()
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FOUND -> {
                stopAlarm()
                if (!listening) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                if (!goForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startListening()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopAlarm()
        stopListening()
        alarm.release()
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun goForeground(): Boolean = try {
        val n = buildNote(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTE_ID, n)
        }
        true
    } catch (e: Exception) {
        error = "Android blocked listening. Open Clap Finder and tap Start."
        false
    }

    private fun buildNote(alarmMode: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
        )
        val b = Notification.Builder(this, if (alarmMode) CH_ALARM else CH_LISTEN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
        if (alarmMode) {
            val found = PendingIntent.getService(this, 1, Intent(this, ClapService::class.java).setAction(ACTION_FOUND), flags)
            b.setContentTitle("Here I am!")
                .setContentText("Tap Found it to stop the alarm")
                .setCategory(Notification.CATEGORY_ALARM)
                .addAction(Notification.Action.Builder(null as Icon?, "Found it", found).build())
        } else {
            val stop = PendingIntent.getService(this, 2, Intent(this, ClapService::class.java).setAction(ACTION_STOP), flags)
            b.setContentTitle("Clap Finder is listening")
                .setContentText("Clap ${prefs.clapsNeeded} times to find your phone")
                .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
        }
        return b.build()
    }

    private fun showNote(alarmMode: Boolean) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTE_ID, buildNote(alarmMode))
        } catch (_: Exception) {
        }
    }

    // ---------- listening ----------

    private fun startListening() {
        if (listening) return
        listening = true
        keepGoing = true
        error = null
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClapFinder:listen").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        worker = Thread { listenLoop() }.also {
            it.name = "clap-listener"
            it.start()
        }
    }

    private fun stopListening() {
        keepGoing = false
        listening = false
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
        }
        worker = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        level = 0f
        clapsInRow = 0
    }

    private fun fail(msg: String) {
        error = msg
        main.post {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun listenLoop() {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec: AudioRecord
        try {
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192)
            )
        } catch (e: Exception) {
            fail("Couldn't open the microphone.")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("Couldn't open the microphone. Is another app using it?")
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            fail("Couldn't start the microphone.")
            return
        }

        var det = ClapDetector(prefs.sensitivity, prefs.clapsNeeded)
        val buf = ShortArray(256)
        val f = FloatArray(256)
        var samples = 0L
        var frames = 0
        var wasMuted = false

        while (keepGoing) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                }
                continue
            }
            samples += n
            // ignore the microphone while our own alarm is making noise
            if (alarming || SystemClock.elapsedRealtime() < muteUntil) {
                level = 0f
                wasMuted = true
                continue
            }
            if (wasMuted) {
                det = ClapDetector(prefs.sensitivity, prefs.clapsNeeded)
                wasMuted = false
            }
            for (i in 0 until n) f[i] = buf[i] / 32768f
            if (++frames % 60 == 0) {
                det.sensitivity = prefs.sensitivity
                det.clapsNeeded = prefs.clapsNeeded
            }
            val r = det.process(f, n, samples.toDouble() / sr)
            level = det.lastPeak
            clapsInRow = det.clapCount
            if (r == 1) lastClapAt = System.currentTimeMillis()
            if (r == 2) main.post { startAlarm() }
        }
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        rec.release()
    }

    // ---------- alarm ----------

    private fun startAlarm() {
        if (alarming) return
        alarming = true
        showNote(true)
        alarm.play(prefs.sound, prefs.speak)
        if (prefs.vibrate) startVibration()
        if (prefs.flash) main.post(torchBlink)
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, prefs.ringSeconds * 1000L)
    }

    private fun stopAlarm() {
        main.removeCallbacks(autoStop)
        if (!alarming) return
        alarming = false
        alarm.stop()
        stopVibration()
        main.removeCallbacks(torchBlink)
        setTorch(false)
        muteUntil = SystemClock.elapsedRealtime() + 1500
        if (listening) showNote(false)
    }

    private fun startVibration() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            vibrator = v
            val a = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0), a)
        } catch (_: Exception) {
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) {
        }
    }
}
