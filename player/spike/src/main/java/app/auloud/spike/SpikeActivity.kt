package app.auloud.spike

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Slice 7 throwaway benchmark (S7B, deleted after the gate).
 *
 * Measures on-device, reports on-screen, exports to
 * `/Auloud/spike-results.txt` (the user pastes it back). Model packs
 * are sideloaded to `/Auloud/spike-models/` by hand:
 * `piper/` (lessac `.onnx` + `tokens.txt` + `espeak-ng-data/`),
 * `kokoro/` (`model.onnx` + `voices.bin` + `tokens.txt` +
 * `espeak-ng-data/`). All work runs off the UI thread; numbers only
 * count from the Tab E.
 */
class SpikeActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = mutableStateOf(listOf("Auloud Slice 7 spike (S7B)."))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureStoragePermission()
        setContent {
            MaterialTheme {
                var log by remember { lines }
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(
                        rememberScrollState()
                    )
                ) {
                    Text("Slice 7 spike", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run("device") { deviceInfo() } }) { Text("Device") }
                        Button(onClick = { run("piper") { benchPiper() } }) { Text("Piper") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run("piper2") { benchTwoPiper() } }) { Text("2x Piper") }
                        Button(onClick = { run("kokoro") { benchKokoro() } }) { Text("Kokoro") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run("system") { benchSystemTts() } }) { Text("System") }
                        Button(onClick = { run("battery") { benchBattery() } }) { Text("10-min") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { export() }) { Text("Export to /Auloud/spike-results.txt") }
                    Spacer(Modifier.height(8.dp))
                    log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun run(tag: String, block: suspend () -> List<String>) {
        emit("--- $tag starting ---")
        scope.launch {
            try {
                val out = block()
                withContext(Dispatchers.Main) { out.forEach { emit(it) } }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { emit("$tag FAILED: ${e.message}") }
            }
            withContext(Dispatchers.Main) { emit("--- $tag done ---") }
        }
    }

    private fun emit(line: String) {
        lines.value = lines.value + line
    }

    private fun ensureStoragePermission() {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1
            )
        }
    }

    private fun modelsRoot(): File =
        File(Environment.getExternalStorageDirectory(), "Auloud/spike-models")

    private fun pssMb(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
        return if (info.isNotEmpty()) info[0].totalPss / 1024L else -1L
    }

    private fun battery(): String {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (100 * level / scale) else -1
        return "battery=$pct% temp=${temp / 10.0}C"
    }

    private fun deviceInfo(): List<String> {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return listOf(
            "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}",
            "abis=${Build.SUPPORTED_ABIS.joinToString(",")}",
            "is64=${android.os.Process.is64Bit()}",
            "totalMemMb=${memInfo.totalMem / 1048576L}",
            "pssMb=${pssMb()}",
            battery()
        )
    }

    private fun piperDir(): File = File(modelsRoot(), "piper")
    private fun kokoroDir(): File = File(modelsRoot(), "kokoro")

    private fun makePiper(modelDir: File): OfflineTts {
        val model = File(modelDir, "en_US-lessac-medium.onnx").takeIf { it.isFile }
            ?: modelDir.listFiles { f -> f.extension == "onnx" }?.firstOrNull()
            ?: throw IllegalStateException("no .onnx in $modelDir")
        val tokens = File(modelDir, "tokens.txt").takeIf { it.isFile }
            ?: throw IllegalStateException("no tokens.txt in $modelDir")
        val dataDir = File(modelDir, "espeak-ng-data").takeIf { it.isDirectory }
            ?: throw IllegalStateException("no espeak-ng-data in $modelDir")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = dataDir.absolutePath
                ),
                numThreads = 2,
                debug = false
            )
        )
        return OfflineTts(config = config)
    }

    private fun makeKokoro(modelDir: File): OfflineTts {
        fun need(name: String): String {
            val file = File(modelDir, name)
            if (!file.exists()) throw IllegalStateException("no $name in $modelDir")
            return file.absolutePath
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = need("model.onnx"),
                    voices = need("voices.bin"),
                    tokens = need("tokens.txt"),
                    dataDir = need("espeak-ng-data")
                ),
                numThreads = 2,
                debug = false
            )
        )
        return OfflineTts(config = config)
    }

    private fun synthTimed(tts: OfflineTts, text: String, sid: Int): Pair<Double, Int> {
        val gen = GenerationConfig(sid = sid, speed = 1.0f)
        val start = System.nanoTime()
        val audio = tts.generateWithConfigAndCallback(text, gen, ::keepGoing)
        val wallMs = (System.nanoTime() - start) / 1_000_000.0
        val audioSec = audio.samples.size.toDouble() / audio.sampleRate.toDouble()
        return audioSec to wallMs.toInt()
    }

    private fun benchPiper(): List<String> {
        val out = mutableListOf("pssBeforeMb=${pssMb()}")
        val loadStart = System.nanoTime()
        val tts = makePiper(piperDir())
        out += "piperLoadMs=${(System.nanoTime() - loadStart) / 1_000_000L} pssAfterLoadMb=${pssMb()}"
        var audioTotal = 0.0
        var wallTotal = 0L
        BENCH_SENTENCES.forEachIndexed { i, sentence ->
            val (audioSec, wallMs) = synthTimed(tts, sentence, 0)
            if (i > 0) {
                audioTotal += audioSec
                wallTotal += wallMs
            }
        }
        out += "piperWarmedRtf=%.2f pssMb=${pssMb()}".format(wallTotal / 1000.0 / audioTotal) +
            " audioSec=%.1f wallSec=%.1f".format(audioTotal, wallTotal / 1000.0)
        tts.release()
        return out
    }

    private fun benchTwoPiper(): List<String> {
        val out = mutableListOf("pssBeforeMb=${pssMb()}")
        val first = makePiper(piperDir())
        out += "pssOneMb=${pssMb()}"
        val second = makePiper(piperDir())
        out += "pssTwoMb=${pssMb()} (delta = two models at once)"
        val (audioSec, wallMs) = synthTimed(first, BENCH_SENTENCES[0], 0)
        val (audioSec2, wallMs2) = synthTimed(second, BENCH_SENTENCES[1], 0)
        out += "twoPiperRtf=%.2f".format((wallMs + wallMs2) / 1000.0 / (audioSec + audioSec2))
        first.release()
        second.release()
        return out
    }

    private fun benchKokoro(): List<String> {
        val out = mutableListOf("pssBeforeMb=${pssMb()}")
        val loadStart = System.nanoTime()
        val tts = makeKokoro(kokoroDir())
        out += "kokoroLoadMs=${(System.nanoTime() - loadStart) / 1_000_000L} pssAfterLoadMb=${pssMb()}"
        var audioTotal = 0.0
        var wallTotal = 0L
        BENCH_SENTENCES.forEachIndexed { i, sentence ->
            // Alternate two speaker ids: same cost as same-sid when switching is free.
            val (audioSec, wallMs) = synthTimed(tts, sentence, i % 2)
            if (i > 0) {
                audioTotal += audioSec
                wallTotal += wallMs
            }
        }
        out += "kokoroWarmedRtf=%.2f pssMb=${pssMb()}".format(wallTotal / 1000.0 / audioTotal) +
            " audioSec=%.1f wallSec=%.1f".format(audioTotal, wallTotal / 1000.0)
        tts.release()
        return out
    }

    private fun benchSystemTts(): List<String> {
        val out = mutableListOf<String>()
        var tts: TextToSpeech? = null
        val ready = java.util.concurrent.CountDownLatch(1)
        var initOk = false
        tts = TextToSpeech(this) { status ->
            initOk = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        if (!ready.await(15, java.util.concurrent.TimeUnit.SECONDS) || !initOk) {
            tts?.shutdown()
            return listOf("systemTts unavailable")
        }
        val engine = tts
        val voices = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
        out += "systemVoices=${voices.size} default=${engine.defaultVoice?.name}"
        if (voices.isEmpty()) {
            engine.shutdown()
            return out + "no offline voices"
        }
        val scratch = File(cacheDir, "spike-tts").apply { mkdirs() }
        val first = voices.first()
        val second = voices.getOrNull(1) ?: first
        var audioTotal = 0.0
        var wallTotal = 0L
        var gaps = 0L
        var gapCount = 0
        BENCH_SENTENCES.take(10).forEachIndexed { i, sentence ->
            val voice: Voice = if (i % 2 == 0) first else second
            engine.voice = voice
            val file = File(scratch, "s$i.wav")
            val start = System.nanoTime()
            val heard = renderSystemUtterance(engine, sentence, file)
            val wallMs = (System.nanoTime() - start) / 1_000_000
            if (i > 0) {
                audioTotal += heard
                wallTotal += wallMs
                if (voice != first) {
                    gaps += wallMs
                    gapCount++
                }
            }
            file.delete()
        }
        out += "systemWarmedRtf=%.2f".format(wallTotal / 1000.0 / audioTotal.coerceAtLeast(0.01))
        out += "systemSwitchGapMs=${if (gapCount > 0) gaps / gapCount else -1L} pssMb=${pssMb()}"
        engine.shutdown()
        return out
    }

    private fun renderSystemUtterance(engine: TextToSpeech, text: String, file: File): Double {
        val latch = java.util.concurrent.CountDownLatch(1)
        val id = java.util.UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                latch.countDown()
            }

            override fun onError(utteranceId: String) {
                latch.countDown()
            }
        })
        engine.synthesizeToFile(text, null, file, id)
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!file.isFile || file.length() == 0L) return 0.0
        // WAV duration from header (16-bit mono typical of synthesizeToFile).
        return try {
            val bytes = file.readBytes()
            val dataSize = java.nio.ByteBuffer.wrap(bytes, 40, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            val sampleRate = java.nio.ByteBuffer.wrap(bytes, 24, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            dataSize.toDouble() / 2.0 / sampleRate.toDouble()
        } catch (_: Exception) {
            0.0
        }
    }

    private fun benchBattery(): List<String> {
        val out = mutableListOf("start ${battery()} pssMb=${pssMb()}")
        val tts = makePiper(piperDir())
        val deadline = System.nanoTime() + 10L * 60L * 1_000_000_000L
        var n = 0
        var audioTotal = 0.0
        while (System.nanoTime() < deadline) {
            val (audioSec, _) = synthTimed(tts, BENCH_SENTENCES[n % BENCH_SENTENCES.size], 0)
            audioTotal += audioSec
            n++
        }
        tts.release()
        out += "10min piper utterances=$n audioSec=%.0f end ${battery()}".format(audioTotal)
        return out
    }

    private fun export() {
        try {
            val root = File(Environment.getExternalStorageDirectory(), "Auloud")
            if (!root.isDirectory) root.mkdirs()
            val file = File(root, "spike-results.txt")
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(java.util.Date())
            file.appendText("\n==== spike $stamp ====\n" + lines.value.joinToString("\n") + "\n")
            emit("exported to ${file.absolutePath}")
        } catch (e: Exception) {
            emit("export FAILED: ${e.message}")
        }
    }

    companion object {
        fun keepGoing(@Suppress("unused") samples: FloatArray): Int = 1

        val BENCH_SENTENCES = listOf(
            "The old lighthouse keeper whispered a secret to the curious child.",
            "Rain hammered the tin roof of the small station all afternoon.",
            "She folded the letter twice and slipped it into her coat pocket.",
            "The market square buzzed with traders shouting prices at dawn.",
            "A black dog slept across the doorway of the empty bakery.",
            "He counted the coins three times before paying the ferryman.",
            "The northern road climbs steeply past the abandoned quarry.",
            "Candles guttered as the great doors swung open without a sound.",
            "They mapped the coastline by lantern light through the winter.",
            "The orchestra tuned in the pit while the audience settled down.",
            "Salt spray crusted the ropes along the harbor railing.",
            "A single crow watched the field from the top of the fence post.",
            "The committee voted just after midnight in a stuffy back room.",
            "Steam rose from the iron pot as the stew began to thicken.",
            "He memorized the harbor timetable before the morning sailing.",
            "The old bridge groaned under the weight of the timber cart.",
            "Snowmelt fed the river until it ran bank-full and brown.",
            "She traced the faded map with one finger, stopping at the mill.",
            "The lighthouse beam swept the channel once every nine seconds.",
            "Winter wheat stood green against the dark November soil."
        )
    }
}
