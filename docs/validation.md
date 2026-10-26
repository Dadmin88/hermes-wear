# Validation and known limits

Current source baseline: 1.0.0-rc10. Updated after manual Galaxy Watch6 feedback.

## Physical feedback

The owner reported working text conversations, audible native speech, the purple/lime interface, in-app recognition and “Hey Hermes” activation. First wake-model preparation took longer than two minutes before activation worked. These are functional observations, not a measured consecutive-run test or statistically valid latency distribution.

## Automated checks

The private rc10 baseline passed watch unit tests, watch APK assembly, lint and original signing-certificate verification. The sanitized public snapshot changes the package name, removes private endpoints/signing paths and disables the private remote-voice adapter. The sanitized snapshot was separately verified before publication: 34 Android unit tests passed (0 failures, errors or skips), both debug APKs assembled, both lint tasks passed, and 22 relay tests passed on Node.js 26.12.1. These checks do not replace physical acceptance of public-package builds.

## Still required for full acceptance

- 50 consecutive physical-watch text turns with ordering, deduplication and terminal/recoverable states verified against native run IDs.
- 20 audible responses across screen/app lifecycle, interruptions, long replies and voice toggling.
- Wake detection accuracy, false positives, battery/thermal measurement and repeated cold/warm starts.
- Phone lock/restart, Bluetooth/Wi-Fi loss, direct/fallback switching and missed-event recovery.
- Relay/Hermes/service restart matrix, long-task completion and physical workstation-off testing.
- End-to-end p50/p95: submission to durable acknowledgment, inference, completion to display and text to audible speech. No measured hardware p95 is claimed.
- Profile filesystem/tool isolation and provider configuration on each new deployment.

The public source is a release candidate, not a claim of universal Wear OS compatibility or always-on hotword support.
