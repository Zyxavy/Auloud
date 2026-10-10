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
import android.speech.tts.UtteranceProgressListener
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
 *
 * Slice 13 (ST0): the Live20/LiveSw/Live2x/LiveSil buttons measure the
 * live `speak()` path instead (utterances to audio out, no file
 * round-trip): inter-utterance gaps at 1.0x/1.5x, one- vs two-instance
 * voice switching, and silent-utterance pause accuracy. Thresholds mirror
 * `StreamGapStats` (median 150 ms, p95 400 ms, no gap over 1 s).
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
                        Button(onClick = { run("systemLong") { benchSystemLong() } }) { Text("SysLong") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run("battery") { benchBattery() } }) { Text("10-min") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run("live20") { benchLive() } }) { Text("Live20") }
                        Button(onClick = { run("livesw") { benchLiveSwitch() } }) { Text("LiveSw") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { run("live2x") { benchLiveTwo() } }) { Text("Live2x") }
                        Button(onClick = { run("livesil") { benchLiveSilent() } }) { Text("LiveSil") }
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

    /**
     * S7 follow-up: novel-length utterances (5x ~60 words) through the
     * default voice only (no switching). Prints standard RTF
     * (audio/wall, higher is better): sustained >1.3 means live
     * streaming is viable, ~1.0 means background-render, below means
     * neither. Restart the app before this run (engines linger in RAM).
     */
    private fun benchSystemLong(): List<String> {
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
        if (voices.isEmpty()) {
            engine.shutdown()
            return listOf("no offline voices")
        }
        val scratch = File(cacheDir, "spike-tts").apply { mkdirs() }
        // Default voice, fixed: this run measures raw synthesis speed,
        // not switching (switching was measured: ~800ms+ per change).
        val utterances = (0 until 5).map { u ->
            BENCH_SENTENCES.drop(u * 4).take(4).joinToString(" ")
        }
        var audioTotal = 0.0
        var wallTotal = 0L
        utterances.forEachIndexed { i, text ->
            val file = File(scratch, "long$i.wav")
            val start = System.nanoTime()
            val heard = renderSystemUtterance(engine, text, file)
            wallTotal += (System.nanoTime() - start) / 1_000_000
            if (i > 0) audioTotal += heard
            file.delete()
        }
        val wallSec = wallTotal / 1000.0
        out += "sysLongRtf=%.2f audioSec=%.1f wallSec=%.1f pssMb=${pssMb()}".format(
            audioTotal / wallSec.coerceAtLeast(0.01), audioTotal, wallSec
        )
        out += "sysLong verdict: " + when {
            audioTotal / wallSec.coerceAtLeast(0.01) >= 1.3 ->
                "STREAMING VIABLE (sustained 1.3x+)"
            audioTotal / wallSec.coerceAtLeast(0.01) >= 1.0 ->
                "BACKGROUND-RENDER ONLY (1.0x+, streaming out)"
            else -> "NEITHER (below 1.0x even on long utterances)"
        }
        engine.shutdown()
        return out
    }

    /**
     * ST0: init one System TTS instance (null when unavailable). Callers
     * own `shutdown()`. Runs off the UI thread (callers are on `scope`).
     */
    private fun awaitSystemTts(): TextToSpeech? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val tts = TextToSpeech(this) { status ->
            ok = status == TextToSpeech.SUCCESS
            latch.countDown()
        }
        if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS) || !ok) {
            tts.shutdown()
            return null
        }
        return tts
    }

    /** ST0: one-line gap summary (mirrors `StreamGapStats` thresholds). */
    private fun gapSummary(tag: String, gaps: List<Long>): String {
        if (gaps.isEmpty()) return "$tag gaps=none"
        val sorted = gaps.sorted()
        val n = sorted.size
        val med = sorted[n / 2]
        val p95 = sorted[((95 * n + 99) / 100 - 1).coerceIn(0, n - 1)]
        val max = sorted[n - 1]
        val verdict = if (med <= 150 && p95 <= 400 && max <= 1_000) "GO" else "NO-GO"
        return "$tag n=$n med=${med}ms p95=${p95}ms max=${max}ms $verdict"
    }

    /**
     * ST0: speak [sentences] back-to-back, recording onStart/onDone per
     * utterance. Returns the report lines: start latency plus the gap
     * distribution (each gap is start[n+1] minus done[n]).
     */
    private fun runLivePass(
        tts: TextToSpeech,
        sentences: List<String>,
        rate: Float,
        tag: String
    ): List<String> {
        tts.setSpeechRate(rate)
        val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val dones = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val latch = java.util.concurrent.CountDownLatch(sentences.size)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                starts[utteranceId] = System.nanoTime()
            }

            override fun onDone(utteranceId: String) {
                dones[utteranceId] = System.nanoTime()
                latch.countDown()
            }

            override fun onError(utteranceId: String) {
                latch.countDown()
            }
        })
        val t0 = System.nanoTime()
        sentences.forEachIndexed { i, sentence ->
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "$tag-$i")
        }
        val finished = latch.await(12, java.util.concurrent.TimeUnit.MINUTES)
        val firstStart = starts["$tag-0"]
        val latency = if (firstStart == null) -1 else (firstStart - t0) / 1_000_000
        val gaps = sentences.indices.drop(1).mapNotNull { i ->
            val done = dones["$tag-${i - 1}"]
            val start = starts["$tag-$i"]
            if (done == null || start == null) null else (start - done) / 1_000_000
        }
        return listOf(
            "$tag rate=$rate finished=$finished startLatency=${latency}ms",
            gapSummary("$tag rate=$rate", gaps)
        )
    }

    /**
     * ST0 Live20: 20 real sentences through `speak()` at 1.0x and 1.5x
     * on the default offline voice. Run with the screen on first (gap
     * numbers), then with the screen off: whether audio continues is
     * itself gate data (a kill means ST4 needs the foreground service).
     */
    private fun benchLive(): List<String> {
        val out = mutableListOf<String>()
        val tts = awaitSystemTts() ?: return listOf("systemTts unavailable")
        val voices = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
        if (voices.isEmpty()) {
            tts.shutdown()
            return listOf("no offline voices")
        }
        out += "liveVoices=${voices.size} default=${tts.defaultVoice?.name}"
        tts.voice = voices.first()
        val sentences = BENCH_SENTENCES.take(20)
        out += runLivePass(tts, sentences, 1.0f, "live20")
        out += runLivePass(tts, sentences, 1.5f, "live20")
        out += "pssMb=${pssMb()} ${battery()}"
        tts.shutdown()
        return out
    }

    /**
     * ST0 LiveSw: narrator/dialogue alternation on ONE instance (voice
     * set per utterance). Reports overall gaps plus switch-only gaps:
     * if switch gaps fail while same-voice gaps pass, the fallback is
     * single-voice streaming (plan decision 3).
     */
    private fun benchLiveSwitch(): List<String> {
        val out = mutableListOf<String>()
        val tts = awaitSystemTts() ?: return listOf("systemTts unavailable")
        val voices = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
        if (voices.size < 2) {
            tts.shutdown()
            return listOf("need 2+ offline voices, have ${voices.size}")
        }
        val narrator = voices[0]
        val dialogue = voices[1]
        out += "liveSw narrator=${narrator.name} dialogue=${dialogue.name}"
        val sentences = BENCH_SENTENCES.take(20)
        val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val dones = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val latch = java.util.concurrent.CountDownLatch(sentences.size)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                starts[utteranceId] = System.nanoTime()
            }

            override fun onDone(utteranceId: String) {
                dones[utteranceId] = System.nanoTime()
                latch.countDown()
            }

            override fun onError(utteranceId: String) {
                latch.countDown()
            }
        })
        sentences.forEachIndexed { i, sentence ->
            tts.voice = if (i % 2 == 0) narrator else dialogue
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "livesw-$i")
        }
        val finished = latch.await(12, java.util.concurrent.TimeUnit.MINUTES)
        out += "liveSw finished=$finished"
        val gaps = sentences.indices.drop(1).mapNotNull { i ->
            val done = dones["livesw-${i - 1}"]
            val start = starts["livesw-$i"]
            if (done == null || start == null) null else (start - done) / 1_000_000
        }
        out += gapSummary("liveSw all", gaps)
        // Odd gaps follow a voice switch (even index starts a new voice).
        val switchGaps = gaps.filterIndexed { i, _ -> i % 2 == 0 }
        out += gapSummary("liveSw switchOnly", switchGaps)
        tts.shutdown()
        return out
    }

    /**
     * ST0 Live2x: narrator/dialogue alternation across TWO instances
     * (one voice each, sequential hand-off on done). Compare with
     * LiveSw: the smaller switch gap wins (plan decision 3).
     */
    private fun benchLiveTwo(): List<String> {
        val out = mutableListOf<String>()
        val first = awaitSystemTts() ?: return listOf("systemTts unavailable")
        val second = awaitSystemTts()
        if (second == null) {
            first.shutdown()
            return listOf("second instance failed")
        }
        val voices = first.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
        if (voices.size < 2) {
            first.shutdown()
            second.shutdown()
            return listOf("need 2+ offline voices, have ${voices.size}")
        }
        val narrator = voices[0]
        val dialogue = voices[1]
        out += "live2x narrator=${narrator.name} dialogue=${dialogue.name}"
        val sentences = BENCH_SENTENCES.take(20)
        val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val dones = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val latch = java.util.concurrent.CountDownLatch(sentences.size)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        fun chainOn(other: TextToSpeech) {
            val i = next.incrementAndGet()
            if (i < sentences.size) {
                other.voice = if (i % 2 == 0) narrator else dialogue
                other.speak(sentences[i], TextToSpeech.QUEUE_ADD, null, "live2x-$i")
            }
        }
        first.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                starts[utteranceId] = System.nanoTime()
            }

            override fun onDone(utteranceId: String) {
                dones[utteranceId] = System.nanoTime()
                latch.countDown()
                chainOn(second)
            }

            override fun onError(utteranceId: String) {
                latch.countDown()
                chainOn(second)
            }
        })
        second.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                starts[utteranceId] = System.nanoTime()
            }

            override fun onDone(utteranceId: String) {
                dones[utteranceId] = System.nanoTime()
                latch.countDown()
                chainOn(first)
            }

            override fun onError(utteranceId: String) {
                latch.countDown()
                chainOn(first)
            }
        })
        first.voice = narrator
        first.speak(sentences[0], TextToSpeech.QUEUE_ADD, null, "live2x-0")
        val finished = latch.await(12, java.util.concurrent.TimeUnit.MINUTES)
        out += "live2x finished=$finished"
        val gaps = sentences.indices.drop(1).mapNotNull { i ->
            val done = dones["live2x-${i - 1}"]
            val start = starts["live2x-$i"]
            if (done == null || start == null) null else (start - done) / 1_000_000
        }
        out += gapSummary("live2x all", gaps)
        first.shutdown()
        second.shutdown()
        return out
    }

    /**
     * ST0 LiveSil: silent-utterance pause accuracy (the streaming
     * equivalent of rendered silence): requested 100/250/1000 ms vs the
     * measured done-minus-start per silent utterance.
     */
    private fun benchLiveSilent(): List<String> {
        val out = mutableListOf<String>()
        val tts = awaitSystemTts() ?: return listOf("systemTts unavailable")
        listOf(100L, 250L, 1000L).forEach { requested ->
            val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val dones = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val latch = java.util.concurrent.CountDownLatch(5)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    starts[utteranceId] = System.nanoTime()
                }

                override fun onDone(utteranceId: String) {
                    dones[utteranceId] = System.nanoTime()
                    latch.countDown()
                }

                override fun onError(utteranceId: String) {
                    latch.countDown()
                }
            })
            repeat(5) { i ->
                tts.playSilentUtterance(requested, TextToSpeech.QUEUE_ADD, "livesil-$requested-$i")
            }
            latch.await(2, java.util.concurrent.TimeUnit.MINUTES)
            val actual = (0 until 5).mapNotNull { i ->
                val start = starts["livesil-$requested-$i"]
                val done = dones["livesil-$requested-$i"]
                if (start == null || done == null) null else (done - start) / 1_000_000
            }
            out += if (actual.isEmpty()) "liveSil ${requested}ms: none completed"
            else "liveSil ${requested}ms: med=${actual.sorted()[actual.size / 2]}ms" +
                " min=${actual.min()}ms max=${actual.max()}ms"
        }
        tts.shutdown()
        return out
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
