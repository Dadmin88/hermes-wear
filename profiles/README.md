# Included Hermes Wear profile

`hermes-wear/` is vendored from the `hermes-wear` distribution in [Hermes Profile Packs](https://github.com/Dadmin88/hermes-profile-packs), source commit `26a20dfecf80a0962c4752764223c36d14bdacb5`. It retains its author attribution, AGPL-3.0-only distribution declaration and [license](LICENSE). The upstream repository link is public attribution, not a live service address.

From this repository root, using a Hermes version supporting native profile distribution installation:

```sh
hermes profile install ./profiles/hermes-wear --name hermes-wear
```

Use a fresh restricted OS user for production and inspect `hermes profile --help` for the installed version. Review an existing profile before updates; never overwrite its memories or configuration blindly. Select an authorized provider/model and authenticate separately in this profile using that version's native configuration/authentication commands. The template deliberately contains no provider credentials or personal preferences.

This creates identity, minimal memory/session-search tool policy and one wrist-interactions skill. It does not install a relay, API server, voice engine or notifications. Runtime capabilities require separate setup. Enforce filesystem and process isolation outside the prompt. Keep memories, sessions and credential state out of version control. Do not symlink these to another profile.
