package app.auloud.player.receive

import app.auloud.player.data.LibraryRepository
import app.auloud.player.storage.BundleStorage
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Slice 20 TR1: UI state for the Receive screen (plain class, cleared in
 * `MainActivity.onDestroy` like the import driver).
 */
data class ReceiveUiState(
    val listening: Boolean = false,
    val ip: String? = null,
    val port: Int = ReceiveServer.RECEIVE_PORT,
    val code: String = "",
    val busy: Boolean = false,
    val lastResult: String? = null,
    val error: String? = null
)

class ReceiveViewModel(
    private val booksRoot: String,
    private val storage: BundleStorage,
    private val library: LibraryRepository,
    private val onImported: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ReceiveUiState())
    val state: StateFlow<ReceiveUiState> = _state
    private var server: ReceiveServer? = null

    /** Binds the receiver and shows a fresh pairing code (no-op when live). */
    fun start() {
        if (server?.isAlive == true) return
        val code = ReceiveServer.newPairingCode()
        val candidate = ReceiveServer(
            port = ReceiveServer.RECEIVE_PORT,
            pairingCode = code,
            booksRoot = booksRoot,
            storage = storage,
            library = library,
            onReceiveStarted = { _state.update { it.copy(busy = true) } },
            onReceiveFinished = { outcome ->
                _state.update { it.copy(busy = false, lastResult = describe(outcome)) }
                if (outcome is ReceiveOutcome.Imported) onImported()
            }
        )
        try {
            candidate.start()
        } catch (e: IOException) {
            _state.update {
                it.copy(
                    listening = false,
                    error = "port ${ReceiveServer.RECEIVE_PORT}: " +
                        "port busy (${e.message ?: "another receiver?"})"
                )
            }
            return
        }
        server = candidate
        _state.update {
            it.copy(
                listening = true,
                ip = ReceiveAddresses.deviceIpv4(),
                code = code,
                error = null
            )
        }
    }

    /** Stops the receiver; leaving the screen calls this (never background). */
    fun stop() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        _state.update { it.copy(listening = false, busy = false) }
    }

    fun clear() {
        stop()
        scope.cancel()
    }

    private fun describe(outcome: ReceiveOutcome): String = when (outcome) {
        is ReceiveOutcome.Imported -> "Imported ${outcome.title}"
        is ReceiveOutcome.Duplicate -> "Already on tablet: ${outcome.title}"
        is ReceiveOutcome.Failed -> outcome.problems.joinToString("; ")
    }
}
