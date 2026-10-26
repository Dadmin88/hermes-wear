# Voice and wake phrase

## Speak and listen

Tap the center waveform to start in-app Android speech recognition. Grant microphone permission. The main interface stays visible, displays partial recognized text and responds to microphone level. Only the final result submits a request. Tap again to finish. Errors and a 45-second limit prevent indefinitely active dictation. The recognition service may use the network; this is separate from local wake detection.

**System dictation (separate screen)** in Settings is an explicit compatibility fallback. Selecting it opens the platform's separate Google or Samsung UI.

Android native TextToSpeech is the default reply voice. Settings retains voice enablement, installed voice selection, rate, preview/testing and optional remote voice controls. Check both enabled state and actual engine/asset availability. Text appears independently of speech. New microphone input interrupts playback; long replies are queued. Native TTS does not require paid cloud synthesis.

The purple/lime waveform uses recognition RMS during listening and an amplitude envelope from TTS PCM callbacks during speech. It is an amplitude visualization, not an exact frequency spectrum or guaranteed speaker-clock synchronization. Engines that omit PCM callbacks use an animated speaking pattern tied to confirmed playback start and stop. The fallback indicates playback activity, not measured audio energy.

## Hey Hermes

1. Open Settings and turn **Hey Hermes wake word** ON.
2. Grant microphone permission, then close Settings.
3. Keep Hermes open with the screen awake. Initial setup downloads and verifies an approximately 40 MB English Vosk model, unpacks it, and loads it into memory. This can take several minutes on a watch. The model is cached in app-private storage for subsequent launches.
4. When **Say Hey Hermes** appears, say “Hey Hermes”, pause for the vibration, then ask your question.

Wake detection is OFF by default. It runs locally and does not upload its microphone audio. After activation, the separately configured Android recognizer handles the question. The wake phrase does not identify the speaker or authorize sensitive actions.

**Foreground only:** it does not wake a closed app or a sleeping screen. Detection pauses for Settings, focus loss, dictation, accepted pending work, native speech and when remote voice is enabled. It resumes when eligible. Turn it OFF to release listening; turning it ON again retries a failed initialization.

The settings switch enables wake detection; the main **Say Hey Hermes** indicator shows when listening is active. **Preparing offline wake word…** means setup is still in progress. If setup does not finish, check device diagnostics for download, storage or model-loading errors.

Vosk uses `vosk-model-small-en-us-0.15`, verified SHA-256 `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`. Model and recognition library are Apache-2.0. See [notices](../THIRD_PARTY_NOTICES.md).

## Limits

False activations, missed activations, battery consumption, temperature, screen-sleep behavior and a representative speaker/noise accuracy matrix remain unmeasured. The listener is not a hardware low-power hotword service. Always-available background wake requires separate implementation and testing.

Remote synthesis has an injectable interface but no configured provider adapter; it is disabled in the relay CLI. Server transcription of compressed recordings is not implemented. Local Android recognition and TTS are the supported path.
