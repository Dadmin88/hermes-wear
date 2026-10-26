# Hermes Wear

A native Kotlin assistant for Wear OS, with an Android companion, a durable authenticated relay, and its own independent Hermes Agent profile.

The interface is a royal-purple and lime retro terminal waveform: tap the center to speak, read replies with the curved Reply control, and open Settings with the curved Settings control, a left swipe, or a long press. Existing voice, connection, history, approval and text controls remain in Settings.

**Status: 1.0.0-rc10, experimental.** Text conversations, audible replies, in-app dictation and foreground “Hey Hermes” detection have been reported working on a physical Samsung Galaxy Watch6. This is a manual functional confirmation, not completion of the full reliability or battery acceptance matrix. See [validation](docs/validation.md).

## What is included

- `wear/`: watch application, AGSL waveform with Canvas fallback, native TTS and in-app recognition, optional local Vosk wake phrase.
- `mobile/`: Android companion for connection provisioning and Data Layer fallback transport.
- `shared/`: encrypted credentials, transport and request policy.
- `relay/`: Node.js relay with SQLite request tracking, per-device authentication, deduplication, recovery and structured approvals.
- `profiles/hermes-wear/`: portable profile from Hermes Profile Packs: identity, minimal tool configuration and wrist interaction skill. No memories, sessions or credentials.

The watch prefers authenticated HTTPS. If direct delivery fails, the companion can relay through Wear OS Data Layer using the same request ID. Accepted work continues on the server after the app closes; reopening reconciles results. Transport preference has not yet been established by comparative hardware benchmarks.

## Start here

1. [Build and install both apps](docs/installation.md).
2. [Configure the server and dedicated profile](docs/server.md).
3. Pair the companion with your own relay, then sync the connection to the watch.
4. [Configure voice and optional wake detection](docs/voice.md).

You need your own Hermes deployment, authorized model provider and HTTPS relay. This source does not connect to the maintainer's infrastructure. Example domains are placeholders.

## Privacy and public builds

This public repository starts with a fresh, sanitized history. It excludes private deployment scripts, server names, private domains, device addresses, personal signing keys, credentials, runtime databases, conversations and memories. Public Android builds use package `io.github.hermeswear`, a separate installation from earlier private builds. Do not uninstall a working private app just to try this source.

[Security](SECURITY.md) · [profile provenance](profiles/README.md) · [third-party notices](THIRD_PARTY_NOTICES.md)

## License and reference

AGPL-3.0-only; see [LICENSE](LICENSE). The included profile retains its upstream AGPL license and attribution. Third-party dependencies retain their own licenses.

Engineering patterns were studied in [bruceleroyjr/hermes-watch](https://github.com/bruceleroyjr/hermes-watch), commit `1e698fa77e6cadd4b11e6a18fb9a47aefbd64947` (MIT): stable request IDs, bounded retry, durable recovery and asynchronous speech. Native Zepp OS code is not used; no substantive reference code was copied.
