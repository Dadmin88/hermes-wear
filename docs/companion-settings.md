# Watch settings from the companion

Install the updated watch and companion apps, then open **Watch settings** near the top of the companion. Tap **Refresh from watch** to read the connected watch's current configuration.

The menu includes watch appearance, spoken replies, wake word, native speech engine and voice, speech rate, pitch, voice previews, speaker testing, last-reply playback, optional remote voice, text input, watch dictation, recent conversations/tasks, retry, cancellation, new chat, approvals, notifications, connection status and license notices. Pairing and relay credentials remain on the companion's main connection screen.

## Changes and confirmation

Preference changes are saved on the watch and confirmed by its reply. A changed revision produces a refresh request instead of overwriting newer settings. A disconnected watch or missing confirmation is shown as an error; no successful save is assumed. Reconnect and Refresh to reconcile an uncertain result. The menu uses a recent cached snapshot while connecting.

Appearance, voice and wake preferences can be saved while the watch app is closed. They apply when it opens or resumes. A running watch activity reloads changed preferences through its normal lifecycle. This may interrupt local speech or recording; accepted backend work remains persistent.

## Watch voice inventory

Engine and voice lists come from the watch, not from the phone. Open Hermes on the watch and allow its engine to initialize, then Refresh if a list is unavailable. Changing the engine resets its selected voice and refreshes inventory when the watch loads the new engine. Rate and pitch use the same ranges as the watch menu.

## Actions and permissions

Speaker previews, playback, dictation, task actions and voice-asset installation require Hermes open on the watch. The companion does not silently launch a background microphone or bypass Android permissions. Microphone and notification permission prompts must be approved on the watch. Wake detection remains foreground-only and requires an awake screen.

Cancellation and new chat require confirmation in the companion. Approvals are bound to the active task, exact approval request and generation. Replayed action messages are deduplicated; a process interruption can report an uncertain outcome instead of repeating the action. A delivery confirmation is not proof that sound was audible or that a backend job completed.

Recent task previews are bounded to twenty entries and up to 2,000 characters per answer. **Continue on watch** restores the selected conversation or reconciles its task using the original request identity. Read longer answers on the watch.

The settings protocol sends configuration and selected conversation previews over the signed Wear OS Data Layer. It does not export device credentials, Hermes API keys or provider credentials. Both apps must use the same application ID and signing certificate.
