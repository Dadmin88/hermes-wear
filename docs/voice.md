# Voice and wake phrase

## Speak and listen

Tap the center waveform to start in-app Android speech recognition. Grant microphone permission. The main interface stays visible, displays partial recognized text and responds to microphone level. Only the final result submits a request. Tap again to finish. Errors and a 45-second limit prevent indefinitely active dictation. The recognition service may use the network; this is separate from local wake detection.

**System dictation (separate screen)** in Settings is an explicit compatibility fallback. Selecting it opens the platform's separate Google or Samsung UI.

Android native TextToSpeech is the default reply voice. Settings retains voice enablement, installed voice selection, rate, preview/testing and optional remote voice controls. Check both enabled state and actual engine/asset availability. Text appears independently of speech. New microphone input interrupts playback; long replies are queued. Native TTS does not require paid cloud synthesis.

The purple/lime waveform uses recognition RMS during listening and an amplitude envelope from TTS PCM callbacks during speech. It is an amplitude visualization, not an exact frequency spectrum or guaranteed speaker-clock synchronization. Engines that omit PCM callbacks may show a quieter animation even when speech is audible.

## Hey Hermes

1. Open Settings and turn **Hey Hermes wake word** ON.
2. Grant microphone permission, then close Settings.
3. Keep Hermes open with the screen awake. Initial setup downloads and verifies an approximately 40 MB English Vosk model, unpacks it, and loads it into memory. This can take several minutes on a watch. The model is cached in app-private storage for subsequent launches.
4. When **Say Hey Hermes** appears, say “Hey Hermes”, pause for the vibration, then ask your question.

Wake detection is OFF by default. It runs locally and does not upload its microphone audio. After activation, the separately configured Android recognizer handles the question. The wake phrase does not identify the speaker or authorize sensitive actions.

**Foreground only:** it does not wake a closed app or a sleeping screen. Detection pauses for Settings, focus loss, dictation, accepted pending work, native speech and when remote voice is enabled. It resumes when eligible. Turn it OFF to release listening; turning it ON again retries a failed initialization.

The settings switch reflects your preference; ON is not proof that the microphone is actively listening. The main ready indicator confirms the listener started. “Preparing offline wake word…” is not a ready state. Current setup lacks granular progress; a delayed preparation status eventually completed and wake detection was manually confirmed. Do not diagnose that message alone as a network failure. If preparation never ends, collect device diagnostics rather than repeatedly changing networks.

Vosk uses `vosk-model-small-en-us-0.15`, verified SHA-256 `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`. Model and recognition library are Apache-2.0. See [notices](../THIRD_PARTY_NOTICES.md).

## Limits

False activations, missed activations, battery consumption, temperature, screen-sleep behavior and a representative speaker/noise accuracy matrix remain unmeasured. The listener is not a hardware low-power hotword service. Always-available background wake requires separate implementation and testing.

The public relay keeps remote synthesis as an injectable interface but ships no private provider helper; remote synthesis is disabled in the CLI. Server transcription of compressed recordings is not implemented. Local Android recognition and TTS are the supported path.
