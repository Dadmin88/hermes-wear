# Server deployment

Deploy the relay and dedicated Hermes profile on an always-on host independent of your development computer.

## Dedicated Hermes runtime

Create a restricted OS account `hermes-wear` with private persistent state. Install a compatible Hermes Agent runtime and the [included profile](../profiles/README.md). Inspect the installed version's native profile paths; do not assume a global profile layout. Configure a responsive model accessible through your own authorized provider, only for this profile.

The reference integration was exercised against Hermes `v0.21.7+503.g7577058` with native Runs/idempotency APIs. Check compatibility before upgrading a shared installation. The profile limits tools to memory and session search and disables deferred tool discovery. System prompts are not an authorization boundary: restrict filesystem access and enabled tools in the runtime.

For the tested API-server configuration family, merge these keys into this dedicated profile's configuration, preserving its tool limits and your chosen model:

```yaml
platforms:
  api_server:
    enabled: true
    extra:
      host: 127.0.0.1
      port: 8643
gateway:
  standalone: true
kanban:
  dispatch_in_gateway: false
```

Configure the API key through Hermes's supported private configuration. Start this profile with `hermes -p hermes-wear gateway run --external-supervisor` under a persistent service manager if supported by your installed version. Bind the unrestricted Hermes API only to loopback.

## Relay

Use Node.js 22.13+; Node 26 was tested. No npm dependencies need installation. Provide these environment variables through a mode-0600 service configuration, never in Git or command-line arguments:

| Variable | Purpose |
| --- | --- |
| `HERMES_API_KEY` | Dedicated profile's private API credential |
| `HERMES_API_URL` | Loopback Hermes API, e.g. `http://127.0.0.1:8643` |
| `WATCH_RELAY_TOKEN` | Independently generated bootstrap value, at least 24 characters; required by constructor; CLI does not accept it as a device credential |
| `WATCH_STORE_PATH` | Absolute persistent SQLite file, e.g. `/var/lib/hermes-wear/relay.sqlite` |
| `WATCH_RELAY_PUBLIC_URL` | Your HTTPS origin; never the unrestricted Hermes API |
| `WATCH_RELAY_PORT` | Loopback relay port; default 8796 |
| `WATCH_HERMES_PROVIDER`, `WATCH_HERMES_MODEL` | Optional profile-specific run overrides; no global provider changes |

Generate new random secrets locally; do not reuse administrator/provider credentials. With the environment loaded, run `node relay/server.mjs`. The relay CLI requires a persistent database, disables legacy shared-token authentication and disables remote synthesis. Test harnesses still allow injected in-memory stores and synthetic credentials.

Run as a dedicated system service with automatic startup, `Restart=on-failure`, private state, a read-only runtime, `NoNewPrivileges`, hidden unrelated homes, CPU/memory/task limits and bounded journal retention. Keep state across upgrades. Protect the SQLite DB and WAL/SHM files; they contain request/conversation text. Back up consistent SQLite snapshots and private profile state outside Git. Coordinate restore of profile and relay state to preserve run mappings.

## HTTPS and pairing

Proxy your TLS origin to relay loopback only. A dedicated hostname is simplest. If using the supported `/hermes-wear` URL prefix, strip that prefix before forwarding to the relay. Do not expose the native Hermes API. Cloudflare Tunnel, an existing TLS reverse proxy or a private VPN ingress can work; availability must not depend on the development workstation.

With the SAME database environment as the service, run locally:

```sh
node relay/pair-admin.mjs create
```

Enter the one-time code in the companion. It expires after ten minutes and redeems at most once. The relay issues a per-device credential; no long shared administrator key is pasted into the APK. Revoke a device with `node relay/pair-admin.mjs revoke device_ID` and pair again to rotate it. Verify revoked credentials fail before relying on rotation.

The relay validates input, bounds request bodies, rate-limits device requests, checks ownership and persists acceptance before Hermes execution. Keep ingress rate limits and firewall rules scoped to this relay. Never log Authorization headers or expose provisioning codes publicly.

## Operations and rollback

`GET /health` is a liveness check, not proof of successful model inference. Monitor service restarts, disk capacity, database writes and accepted-to-completed latency. Pair a test device and verify a real turn after deployment. Keep prior runtime artifacts and back up state before upgrading. Roll back code using the same compatible database and profile; never silently reset state to fix an error. Relay restart recovers admission metadata; a Hermes restart can interrupt inference and must show an interrupted result rather than duplicate execution.

Long accepted tasks continue server-side. Hiding or closing the app is not cancellation. Structured approvals are bound to exact native run/request IDs; the included minimal profile has no consequential integrations enabled. Adding tools requires server-enforced permissions, not just approval UI.
