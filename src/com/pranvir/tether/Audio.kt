package com.pranvir.tether

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Effects via SoundPool; the synthwave stems are mixed live so the arrangement follows the climb.
 * Slow-motion muffles the mix, and a crash winds the tape down like a dying deck.
 */
class Audio(context: Context) {
    private val appCtx = context.applicationContext
    private val pool: SoundPool
    private val ids = IntArray(Sfx.COUNT) { -1 }
    private val loaded = ConcurrentHashMap<Int, Boolean>()

    @Volatile private var soundOn = true
    @Volatile private var musicOn = true
    @Volatile private var resumed = false
    @Volatile private var released = false
    @Volatile private var scene = 0
    @Volatile private var combo = 0
    @Volatile private var intensity = 0f
    @Volatile private var slow = false
    @Volatile private var dead = false
    @Volatile private var stems: Array<ShortArray>? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val lock = Any()

    init {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attrs).build()
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded[sampleId] = true }
        Thread({ prepare() }, "tether-audio-prep").apply { isDaemon = true; start() }
    }

    private fun prepare() {
        try {
            val dir = File(appCtx.cacheDir, "audio_v1").apply { mkdirs() }
            for (id in 0 until Sfx.COUNT) {
                if (released) return
                val f = File(dir, "s$id.wav")
                if (!f.exists() || f.length() < 50) writeAtomic(f, Synth.wav(Synth.render(id)))
                ids[id] = pool.load(f.path, 1)
            }
            stems = Array(Stem.COUNT) { s -> cached(File(dir, "stem$s.pcm")) { Synth.renderStem(s) } }
            startMixer()
        } catch (e: Throwable) {
            android.util.Log.e("Tether", "audio prep failed", e)
        }
    }

    private fun cached(f: File, make: () -> ShortArray): ShortArray {
        if (f.exists() && f.length() == Synth.LOOP_N * 2L) {
            try {
                val bytes = f.readBytes()
                return ShortArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
            } catch (e: Exception) { }
        }
        val pcm = make()
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { bytes[i * 2] = (pcm[i].toInt() and 255).toByte(); bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte() }
        writeAtomic(f, bytes)
        return pcm
    }

    private fun writeAtomic(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun play(id: Int, vol: Float, rate: Float) {
        if (!soundOn || released || id !in 0 until Sfx.COUNT) return
        val s = ids[id]
        if (s <= 0 || loaded[s] != true) return
        pool.play(s, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun setEnabled(sound: Boolean, music: Boolean) { soundOn = sound; musicOn = music }
    fun setState(sc: Int, cb: Int, inten: Float, sl: Boolean, dd: Boolean) { scene = sc; combo = cb; intensity = inten; slow = sl; dead = dd }

    fun resume() {
        resumed = true
        if (!released) pool.autoResume()
        synchronized(lock) { try { track?.play() } catch (e: Throwable) {} }
    }

    fun pause() {
        resumed = false
        if (!released) pool.autoPause()
        synchronized(lock) { try { track?.pause() } catch (e: Throwable) {} }
    }

    private fun startMixer() {
        synchronized(lock) {
            if (released || track != null) return
            val minBuf = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Synth.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            if (resumed) t.play()
            val th = Thread({ mix(t) }, "tether-mixer")
            th.isDaemon = true
            thread = th
            th.start()
        }
    }

    private fun mix(t: AudioTrack) {
        val st = stems ?: return
        val chunk = ShortArray(1024)
        val m = Mixer(st)
        while (!released) {
            if (!resumed) { try { Thread.sleep(60) } catch (e: InterruptedException) { break }; continue }
            m.render(chunk, scene, combo, intensity, slow, dead, musicOn)
            val w = try { t.write(chunk, 0, chunk.size) } catch (e: Throwable) { -1 }
            if (w < 0) break
        }
    }

    fun release() {
        released = true
        synchronized(lock) {
            val t = track
            track = null
            try { t?.pause(); t?.flush(); t?.stop() } catch (e: Throwable) {}
            try { thread?.join(400) } catch (e: Throwable) {}
            try { t?.release() } catch (e: Throwable) {}
            thread = null
        }
        try { pool.release() } catch (e: Throwable) {}
    }
}

