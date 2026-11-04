package tech.dmconsulting.voxbulle.transcription

/** Outcome of a Voxtral call. Failures are values, not exceptions, so every caller handles them. */
sealed interface VoxtralResult<out T> {
    data class Success<T>(val value: T) : VoxtralResult<T>

    data class Failure(val error: VoxtralError) : VoxtralResult<Nothing>
}

/** What went wrong, each mapped to a message that says what to do (charter § 7, screen 3). */
enum class VoxtralError {
    /** No network or timeout: « Pas de connexion. Réessayez dans un instant. » */
    NoConnection,

    /** Only silence in the audio: « Je n'ai rien entendu. Réessayez. » */
    NothingHeard,

    /** Rejected key, quota or server error on Mistral's side. */
    Service,
}
