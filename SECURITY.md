# Security and privacy

Use your own dedicated profile and per-device relay credentials. Never publish APK signing keys, provider credentials, API credentials, pairing codes, device addresses, profile memories, conversation databases or logs. Store secrets outside the checkout with restrictive permissions. Keep request databases and profile state private and back them up safely.

Microphone access is explicit. Optional Vosk wake detection runs locally in the foreground. Android dictation may use its configured provider/network. Native TTS may require downloaded voice assets. Wake detection does not authenticate a person or approve an action.

Do not publish sensitive vulnerability details, service addresses or credentials in an issue. Use GitHub private vulnerability reporting if enabled; otherwise contact a maintainer privately before disclosing exploit details. This project is experimental and has no completed independent security audit.
