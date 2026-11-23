package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VoiceUiState(
    val isListening: Boolean = false,
    val rmsLevel: Float = 0f,
    val partialTranscript: String = "",
    val errorMessage: String? = null
)

class VoiceInputManager(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(languageTag: String?) {
        if (!isRecognitionAvailable()) {
            _state.update {
                it.copy(
                    isListening = false,
                    errorMessage = "Speech recognition service is not available on this device."
                )
            }
            return
        }

        stopListeningInternal()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.update {
                    it.copy(
                        isListening = true,
                        rmsLevel = 0.1f,
                        partialTranscript = "",
                        errorMessage = null
                    )
                }
            }

            override fun onBeginningOfSpeech() {
                _state.update { it.copy(isListening = true, errorMessage = null) }
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.08f, 1f)
                _state.update { it.copy(rmsLevel = normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _state.update { it.copy(rmsLevel = 0f) }
            }

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network timeout."
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Try speaking again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input heard."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
                    SpeechRecognizer.ERROR_CLIENT -> null
                    else -> "Voice input error ($error)."
                }
                _state.update {
                    it.copy(
                        isListening = false,
                        rmsLevel = 0f,
                        partialTranscript = "",
                        errorMessage = message
                    )
                }
                destroyRecognizer()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()?.trim().orEmpty()
                _state.update {
                    it.copy(
                        isListening = false,
                        rmsLevel = 0f,
                        partialTranscript = "",
                        errorMessage = null
                    )
                }
                if (transcript.isNotEmpty()) {
                    onFinalResult(transcript)
                }
                destroyRecognizer()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotEmpty()) {
                    _state.update { it.copy(partialTranscript = partial) }
                    onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (!languageTag.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            }
        }

        _state.update { it.copy(isListening = true, errorMessage = null, partialTranscript = "") }
        runCatching {
            recognizer.startListening(intent)
        }.onFailure { err ->
            _state.update {
                it.copy(
                    isListening = false,
                    errorMessage = err.localizedMessage ?: "Failed to start voice input."
                )
            }
            destroyRecognizer()
        }
    }

    fun stopListening() {
        runCatching { speechRecognizer?.stopListening() }
        _state.update { it.copy(isListening = false, rmsLevel = 0f) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun stopListeningInternal() {
        runCatching { speechRecognizer?.cancel() }
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    fun release() {
        stopListeningInternal()
    }
}
