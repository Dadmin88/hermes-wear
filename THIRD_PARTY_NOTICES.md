# Third-party notices

- The profile in `profiles/hermes-wear` comes from Hermes Profile Packs, commit `26a20dfecf80a0962c4752764223c36d14bdacb5`; author Hermes Profile Packs contributors; AGPL-3.0-only. License retained in `profiles/LICENSE`.
- Hermes Agent is a separately installed dependency; consult its upstream license and terms. No provider credentials are distributed.
- Vosk Android and `vosk-model-small-en-us-0.15`: Apache-2.0. The model is downloaded separately from https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip; its checksum is pinned in source. Apache license text is included at `wear/src/main/res/raw/wake_license.txt` and accessible in Settings.
- JNA: LGPL-2.1-or-later / Apache-2.0 dual license; consult the resolved artifact notices. Android/Google libraries and build/test tools retain their upstream licenses.
- The MIT-licensed Amazfit reference https://github.com/bruceleroyjr/hermes-watch was studied at commit `1e698fa77e6cadd4b11e6a18fb9a47aefbd64947`. No substantive code was copied into this implementation; preserve its attribution if adapting code later.

No third-party license is replaced by this repository's AGPL license.
