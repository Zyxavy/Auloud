package app.auloud.player.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PW8: audition playback seam (book playback stays in the service).
 *
 * Audition clips are seconds long and must never disturb the book
 * session, so they play on a throwaway `AudioTrack`, not through
 * ExoPlayer. Tests inject a fake; the device runs [AudioTrackAudioPlayer].
 */
interface AudioPlayer {
    fun play(audio: SynthesizedAudio)
    fun stop()
    fun release()
}

/**
 * PW8 production player: one-shot static `AudioTrack` per preview.
 *
 * Float PCM converts to 16-bit (the ancient `write(byte[],…)` path is
 * API-1 safe; float writes need newer overloads). A new preview stops
 * the previous one; [release] drops the track (ViewModel `clear()`).
 * Needs the device (audition is a PW8 device check).
 */
class AudioTrackAudioPlayer : AudioPlayer {

    private var track: AudioTrack? = null

    override fun play(audio: SynthesizedAudio) {
        stop()
        val frames = audio.samples.size
        if (frames <= 0) return
        val pcm16 = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        audio.samples.forEach { sample ->
            pcm16.putShort((sample * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        val bytes = pcm16.array()
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            player.write(bytes, 0, bytes.size)
            player.play()
            track = player
        } catch (_: Exception) {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    override fun stop() {
        val player = track
        track = null
        if (player == null) return
        try {
            player.stop()
            player.flush()
            player.release()
        } catch (_: Exception) {
        }
    }

    override fun release() = stop()
}
