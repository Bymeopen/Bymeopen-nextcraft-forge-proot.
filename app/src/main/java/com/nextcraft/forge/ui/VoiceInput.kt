package com.nextcraft.forge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.nextcraft.forge.R
import java.util.Locale

/** What a spoken sentence means in the chat composer. */
internal sealed interface VoiceCommand {
    /** Words to put in the message box; [send] when the speaker ended with a send phrase. */
    data class Dictate(val text: String, val send: Boolean) : VoiceCommand
    data object Stop : VoiceCommand
    data object Clear : VoiceCommand
}

/**
 * Turns a recognised sentence into a [VoiceCommand]. Whole-sentence commands (stop, clear, send) are matched exactly
 * so that dictating a normal sentence never triggers them; sending after dictation needs the phrase "send now".
 */
internal object VoiceCommands {
    private val stopPhrases = setOf("stop", "stop agent", "berhenti", "hentikan", "hentikan agen", "hentikan agent")
    private val clearPhrases = setOf("clear", "clear text", "hapus teks", "bersihkan", "bersihkan teks")
    private val sendOnly = setOf("send", "send message", "kirim", "kirim pesan")
    private val sendSuffixes = listOf("send now", "kirim sekarang")

    fun parse(raw: String): VoiceCommand {
        val text = raw.trim()
        val key = text.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ',').trim()
        return when {
            key in stopPhrases -> VoiceCommand.Stop
            key in clearPhrases -> VoiceCommand.Clear
            key in sendOnly -> VoiceCommand.Dictate("", send = true)
            else -> {
                val suffix = sendSuffixes.firstOrNull { key == it || key.endsWith(" $it") }
                if (suffix == null) {
                    VoiceCommand.Dictate(text, send = false)
                } else {
                    val body = text.trimEnd('.', '!', '?', ',').trim().dropLast(suffix.length).trim().trimEnd(',', '.')
                    VoiceCommand.Dictate(body, send = true)
                }
            }
        }
    }
}

/** Speech-to-text for the composer: partial words while speaking, the final sentence when the speaker pauses. */
@Stable
internal class VoiceController(
    private val context: Context,
    private val languageTag: () -> String,
    private val onPartial: State<(String) -> Unit>,
    private val onFinal: State<(String) -> Unit>,
) {
    var listening by mutableStateOf(false)
        private set
    internal var requestPermission: () -> Unit = {}
    private var recognizer: SpeechRecognizer? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { onPartial.value(it) }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            firstResult(results)?.let { onFinal.value(it) }
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                // Silence or nothing recognised is not worth a message; a cancelled client is our own stop.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> Unit
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> toast(R.string.voice_permission_denied)
                else -> toast(R.string.voice_error)
            }
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun toast(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()

    fun toggle() {
        if (listening) {
            stop()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            requestPermission()
        }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            toast(R.string.voice_unavailable)
            return
        }
        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
        listening = true
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            toast(R.string.voice_error)
        }
    }

    /** Ends the recording; the recogniser still delivers what it heard so far. */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }
}

/**
 * Creates the [VoiceController] of a composer. [languageCode] is the app language ("system" follows the device);
 * the callbacks always see the latest composer state.
 */
@Composable
internal fun rememberVoiceController(
    languageCode: String,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
): VoiceController {
    val context = LocalContext.current
    val partial = rememberUpdatedState(onPartial)
    val finalState = rememberUpdatedState(onFinal)
    val language = rememberUpdatedState(languageCode)
    val controller = remember {
        VoiceController(
            context = context,
            languageTag = { language.value.takeUnless { it == "system" || it.isBlank() } ?: Locale.getDefault().toLanguageTag() },
            onPartial = partial,
            onFinal = finalState,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.start() else Toast.makeText(context, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
    }
    controller.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return controller
}
