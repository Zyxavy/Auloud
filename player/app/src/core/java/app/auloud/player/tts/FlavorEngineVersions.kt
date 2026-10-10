package app.auloud.player.tts

/**
 * VC1 (D-126 option A): `core` has no bundled Piper engine, so the
 * piper namespace has no live version here. Null reads stale-ward
 * (an unbuildable expectation classifies stale, per VS2), and renders
 * needing piper voices already refuse earlier with the missing-engine
 * message (VS6 guards).
 */
internal fun piperEngineVersion(): String? = null
