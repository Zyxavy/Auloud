package app.auloud.player.reader

import kotlinx.coroutines.flow.StateFlow

/**
 * ST5: live audio attachment for the unrendered reader.
 *
 * Plain holder (no Android): [sid] is the spoken sentence (null when no
 * stream is speaking); the commands drive the stream path through the
 * service. [restartAt] takes the 0-based sentence index in the loaded
 * chapter (positions are indexes on the stream path); [seekToChapter]
 * takes the 0-based manifest chapter position. Tests build fakes with a
 * [StateFlow] plus recording lambdas; the screen builds the production
 * one from the [app.auloud.player.playback.PlaybackController].
 *
 * API 24 safe: coroutines only.
 */
class LiveStream(
    val sid: StateFlow<Int?>,
    val restartAt: (sentenceIndex: Int) -> Unit,
    val seekToChapter: (chapterPos: Int) -> Unit,
    val nextChapter: () -> Unit,
    val previousChapter: () -> Unit,
)
