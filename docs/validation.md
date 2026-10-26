# Validation and known limits

Version: 1.0.0-rc13 (release candidate).

## Automated verification

- 51 Android unit tests passed, with no failures, errors or skips.
- Watch and phone debug APKs built successfully.
- Watch and phone lint checks passed.
- 22 relay tests passed on Node.js 26.12.1.

## Device compatibility

Text conversations, native speech output, in-app recognition and foreground “Hey Hermes” activation have been manually confirmed on a Samsung Galaxy Watch6. Compatibility with other Wear OS devices remains unverified.

## Known limits

- Wake detection requires Hermes to remain open with the screen awake. It does not activate from a closed app or sleeping screen.
- Initial wake-model setup downloads approximately 40 MB and can take several minutes.
- Wake accuracy, false activations, battery use and temperature have not been benchmarked.
- Full consecutive-turn reliability, audible-response and lifecycle/reconnection testing remain incomplete.
- Comparative direct/companion transport benchmarks and end-to-end latency percentiles are not yet available.
- Long-task recovery across relay, Hermes and service restarts requires broader device testing.
- New server deployments require their own profile-isolation and permission checks.

Automated checks do not establish audible playback, hardware latency or battery performance.

Companion-to-watch settings synchronization and the playback animation require physical-device review for this release.
