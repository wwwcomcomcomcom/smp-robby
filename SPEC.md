# smp-robby — Implementation Spec

Minecraft lobby + DataGSM-OAuth authentication system across a Velocity proxy network.

> Status: design agreed via planning interview (2026-07-01). This is the build contract.

---

## 1. Decisions summary

| Area | Decision |
|------|----------|
| Build | Gradle, **Kotlin DSL**, multi-module |
| JVM | **Java 25** toolchain (Kotlin module targets JVM 25) |
| Lobby | **Minestom** (Java) |
| Content servers | **Paper/Spigot (Bukkit)** |
| Auth-server | **Ktor** (Kotlin) + **SQLite** (Exposed) |
| OAuth provider | **DataGSM** — official SDK `com.github.themoment-team:datagsm-oauth-sdk-java:1.5.0` (JitPack) |
| Identity model | **Online-mode Minecraft** (stable Mojang UUID is the anchor); OAuth **enriches** with GSM student data |
| Link persistence | **Persistent** `uuid → student` in SQLite; **snapshot at first link, never refreshed** |
| Runtime authority | **Velocity-centric** — Velocity calls auth-server by UUID on join, holds state, serves content servers |
| Delivery to content servers | **Pull on join** — content-lib requests over plugin messaging, Velocity replies |
| Auth key | **8 chars, single-use, 5-min TTL, safe charset** (no `0 O 1 l I`) |
| Server↔server REST auth | **Static shared secret** in `Authorization` header (HTTPS) |
| Gating | Lobby always accessible; **transfer to any content server blocked until linked** |
| Wire format | **JSON** (Gson) for both REST and plugin messaging |
| Root namespace | `iieiiergn.smpAuth` *(as provided; Java convention prefers all-lowercase — rename freely)* |
| Group id | `iieiiergn` |

**User-supplied prerequisites** (not in code):
- DataGSM client registered at `https://www.datagsm.kr/clients` → `clientId`, `clientSecret`.
- A **public callback URL** for the auth-server (e.g. `https://auth.example.kr/callback`) registered as the redirect URI.
- A **Velocity modern-forwarding secret** shared by proxy + lobby.
- A **shared secret** for server→auth-server REST calls.

---

## 2. Module layout

```
smp-robby/                      (root, no code)
├── settings.gradle.kts
├── build.gradle.kts            (common conventions, Java 25 toolchain)
├── common/                     Java — DTOs, channel & protocol constants, JSON codec
│       package iieiiergn.smpAuth.common
├── auth-server/                Kotlin — Ktor web app + DataGSM OAuth + SQLite
│       package iieiiergn.smpAuth.auth
├── lobby-server/               Java — Minestom app (Velocity modern forwarding)
│       package iieiiergn.smpAuth.lobby   (depends on common)
├── velocity-plugin/            Java — Velocity plugin (state + gating + HTTP client)
│       package iieiiergn.smpAuth.velocity (depends on common)
├── content-lib/                Java — Paper/Bukkit library for content servers
│       package iieiiergn.smpAuth.paperlib (depends on common, shades it relocated)
└── sample-content-plugin/      Java — example Paper plugin using content-lib (demo + guide source)
```

**Build notes**
- `common` is a plain library; downstream plugins **shade** it. `content-lib` relocates `common` + Gson so consuming plugins don't class-conflict.
- Shadow plugin (`com.gradleup.shadow`) for `velocity-plugin`, `lobby-server`, `content-lib`, `auth-server`.
- Gson is the single JSON lib (already on the classpath of Velocity, Paper, and Minestom).
- HTTP clients in lobby & velocity use the JDK `java.net.http.HttpClient` (no extra dep).

---

## 3. End-to-end auth flow

### 3.1 First-time link
```
Player (lobby) ──/login──> Lobby shows configured auth URL (clickable) + "run /verify <KEY>"
Player (browser) ──GET /login──> auth-server: make state+PKCE, redirect to DataGSM authorize
DataGSM ──redirect──> auth-server GET /callback?code&state
auth-server: exchangeCodeForToken → getUserInfo (student data)
auth-server: generate 8-char KEY → store KEY→studentData (TTL 5m, single-use) → render HTML page showing KEY
Player (lobby) ──/verify <KEY>──> Lobby POST /api/bind {key,uuid,username}  [Bearer secret]
auth-server: consume key → persist uuid→studentData (snapshot) → 200 {student}
Lobby: success msg; send plugin message LINK_UPDATED{uuid} to Velocity
Velocity: GET /api/links/{uuid} → cache studentData in authState
```

### 3.2 Returning player (already linked)
```
Player connects ──PostLoginEvent──> Velocity GET /api/links/{uuid}
  200 → authState[uuid]=studentData ; 404 → unlinked
Player switches to content server ──ServerPreConnectEvent──>
  target==lobby → allow
  target!=lobby && uuid∉authState → CANCEL + "Link first with /login in the lobby"
  else allow
Content server ──PlayerJoinEvent──> content-lib sends AUTH_REQUEST (player-bound)
Velocity ──PluginMessageEvent──> reply AUTH_RESPONSE{studentData}
content-lib: cache + fire AuthDataLoadedEvent(player, studentData)
```

---

## 4. `common` module

DTOs (Gson-serializable, immutable):

- `StudentData`
  - from `UserInfo`: `datagsmId` (Long, = UserInfo.id), `email`, `role` (String), `isStudent` (Boolean)
  - from `Student`: `name`, `studentNumber`, `grade`, `classNum`, `number`, `sex`, `major`,
    `dormitoryFloor`, `dormitoryRoom`, `majorClub`, `autonomousClub`, `githubId`, `githubUrl`
  - nullable when `isStudent == false`
- Plugin-message protocol:
  - `Channel.NAME = "smpauth:data"` (namespaced, lowercase)
  - `MessageType { AUTH_REQUEST, AUTH_RESPONSE, LINK_UPDATED }`
  - Envelope JSON: `{ "type": "...", "uuid": "...", "linked": bool, "student": {…}|null }`
  - `ProtocolCodec` — encode/decode envelope ↔ `byte[]` (UTF-8 JSON).
- REST DTOs: `BindRequest{key,uuid,username}`, `BindResponse{student}`, `LinkResponse{linked,student}`.

---

## 5. `auth-server` (Ktor + SQLite)

### 5.1 Browser-facing endpoints
- `GET /login` — create `state` + PKCE verifier, store in in-memory pending map (TTL 5m), redirect to
  `DataGsmOAuthClient.createAuthorizationUrl(redirectUri).state(state).enablePkce().build()`.
- `GET /callback?code&state` — validate `state`, `exchangeCodeForToken(code, redirectUri)`,
  `getUserInfo(accessToken)`, map → `StudentData`, generate **KEY**, store `KEY→StudentData`
  (TTL 5m, single-use), render a simple HTML page that displays the KEY prominently.

### 5.2 Server-to-server REST (all require `Authorization: Bearer <sharedSecret>`)
- `POST /api/bind` `{key, uuid, username}` →
  - key missing/expired/used → `410 Gone`
  - else: consume key, **upsert** `links` row (snapshot), `200 {student}`.
- `GET /api/links/{uuid}` → `200 {linked:true, student}` or `404 {linked:false}`.

### 5.3 Persistence (Exposed, SQLite file)
`links` table:
| col | type | note |
|-----|------|------|
| `uuid` | varchar PK | Mojang UUID |
| `username` | varchar | last seen name |
| `datagsm_id` | long | indexed |
| `grade` / `class_num` / `student_number` | int/varchar | queryable convenience |
| `student_json` | text | full `StudentData` snapshot (served verbatim) |
| `linked_at` | timestamp | |

- **No refresh tokens stored, no re-sync** — snapshot is permanent until re-link overwrites it.
- Key store + pending-OAuth store are **in-memory** `ConcurrentHashMap` with scheduled eviction (5-min TTL).

### 5.4 Config (`application.conf`, HOCON; secrets via env)
`datagsm.clientId/clientSecret`, `oauth.redirectUri`, `server.publicBaseUrl`, `security.sharedSecret`,
`db.path`, `key.ttlSeconds=300`, `key.length=8`.

---

## 6. `velocity-plugin`

- State: `ConcurrentHashMap<UUID, StudentData> authState`.
- `PostLoginEvent` → async `GET /api/links/{uuid}`; populate on 200.
- `DisconnectEvent` → evict.
- `ServerPreConnectEvent` → allow if `target == lobbyServerName` **or** `uuid ∈ authState`; else cancel with a
  user message. (Default: every non-lobby server is gated.)
- `PluginMessageEvent` on `smpauth:data`:
  - `AUTH_REQUEST` (from a backend, player-bound) → reply `AUTH_RESPONSE{linked,student}` to that player's server.
  - `LINK_UPDATED{uuid}` (from lobby) → async `GET /api/links/{uuid}`, update `authState`.
- HTTP via JDK `HttpClient`, Bearer shared secret.
- Config (`config.toml`): `authServerBaseUrl`, `sharedSecret`, `lobbyServerName`, optional explicit `gatedServers` list.

---

## 7. `lobby-server` (Minestom)

- Enable **Velocity modern forwarding** (`VelocityProxy.enable(secret)`); online-mode identities arrive via proxy.
- `/login` — display configured auth URL as a clickable component + instruction to run `/verify <KEY>`.
- `/verify <KEY>` — `POST /api/bind {key, uuid, username}` (Bearer secret):
  - success → show `name / grade / class`; send `LINK_UPDATED{uuid}` plugin message to Velocity.
  - failure → explain expired/invalid key, suggest re-running `/login`.
- Optional: on join, lobby may also `AUTH_REQUEST` its own player to display link status.
- Config: `auth.loginUrl`, `velocity.secret`, bind address/port.

---

## 8. `content-lib` (Paper/Bukkit)

- Registers incoming + outgoing plugin channel `smpauth:data`.
- `PlayerJoinEvent` → send `AUTH_REQUEST` (player-bound).
- On `AUTH_RESPONSE` → cache `StudentData`; fire custom `AuthDataLoadedEvent(player, studentData)`.
- `PlayerQuitEvent` → evict.
- Public API:
  - `SmpAuth.get(Player) : Optional<StudentData>`
  - `SmpAuth.isLinked(Player) : boolean`
  - `AuthDataLoadedEvent` for reactive use.
- Shades+relocates `common` and Gson to avoid conflicts in host plugins.

---

## 9. Deliverable: content-server usage guide

`docs/CONTENT-SERVER-GUIDE.md` (Markdown), written against `sample-content-plugin`, covering:
1. Add the JitPack/local dependency on `content-lib`.
2. Channel auto-registration (nothing to configure).
3. Read data: `SmpAuth.get(player)` and the `AuthDataLoadedEvent`.
4. Worked example: gate a command by `student.grade`, greet by `student.name`.
5. Caveats: data may be absent for a tick after join (use the event), unlinked players can't reach content servers anyway (Velocity gate).

---

## 10. Security notes
- All server↔auth-server traffic over HTTPS with Bearer shared secret; never expose `/api/*` publicly without it.
- Key: single-use, 5-min TTL, safe charset, rate-limit `/api/bind` per source.
- Plugin messaging is trusted only because the network is internal + Velocity validates the player binding; never trust client-supplied UUIDs over the channel — Velocity uses the connection's own player.
- Velocity forwarding secret + DataGSM client secret kept out of VCS (`*.example` config files committed).

---

## 11. Implementation phases
1. **Scaffold** — Gradle multi-module, Java 25 toolchain, Kotlin for auth-server, shadow config; `common` DTOs + protocol.
2. **auth-server** — Ktor, DataGSM SDK wiring, callback HTML, key store, SQLite/Exposed, REST API.
3. **velocity-plugin** — state, HTTP client, gating, plugin-messaging handlers.
4. **lobby-server** — Minestom + forwarding, `/login` + `/verify`, plugin messaging.
5. **content-lib** — pull-on-join, cache, `SmpAuth` API + event.
6. **sample-content-plugin + guide** — demo usage; write `CONTENT-SERVER-GUIDE.md`.
7. **E2E** — register DataGSM client, run auth-server + Velocity + lobby + a Paper content server, verify full flow.

---

## 11a. Implementation notes / deviations (as built)
- **Kotlin 2.3.21** (not 2.2.x): required for JVM target 25; also matches Gradle 9.6.1's bundled Kotlin.
- **auth-server uses plain JDBC** (sqlite-jdbc) rather than Exposed, avoiding the Exposed 1.x API churn;
  the `links` table stores a full `StudentData` JSON snapshot plus a few queryable columns.
- **content-lib is a standalone Paper plugin** named `SmpAuth` that content plugins `depend` on, rather
  than a library shaded into each content plugin. It is the single runtime owner of `StudentData`/`AuthMessage`;
  content plugins `compileOnly` it and access the data via the static `SmpAuth` API + `AuthDataLoadedEvent`.
- **DataGSM SDK**: real package is `team.themoment.datagsm.sdk.oauth`; entry point
  `DataGsmOAuthClient.builder(id, secret)…build()`; PKCE via `createAuthorizationUrl(redirectUri).enablePkce()`
  with `getCodeVerifier()` stashed by `state`, then `exchangeCodeForToken(code, redirectUri, verifier)`.
  Authorize endpoint resolves to `https://oauth.authorization.datagsm.kr/v1/oauth/authorize`.
- **Minestom Velocity forwarding**: configured via `MinecraftServer.init(new Auth.Velocity(secret))`
  (the old `extras.velocity.VelocityProxy` is gone in `2026.06.20-26.1.2`).
- **Toolchain**: built with brew `openjdk@25` for compilation; Gradle launched on JDK 26. Velocity
  plugin descriptor is auto-generated by the `@Plugin` annotation processor.
- **Verified**: full `./gradlew build` green; auth-server REST smoke-tested (401 unauth, `linked:false`
  authed, `410` bad key, `302` PKCE redirect to DataGSM). End-to-end OAuth needs real client credentials.

## 12. Open items to confirm before/at coding
- Confirm root namespace spelling `iieiiergn.smpAuth` (camelCase is legal but unconventional for Java packages).
- Confirm Velocity server names (e.g. `lobby`, content server ids) for the gating config.
- Confirm whether the auth-server callback page needs school branding / Korean copy.
- Verify Kotlin + Minestom + Velocity all build cleanly on the **Java 25** toolchain in your environment (all support 21+; 25 expected fine).
