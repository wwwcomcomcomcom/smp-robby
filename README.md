# smp-robby

Minecraft lobby + **DataGSM-OAuth** authentication across a Velocity proxy network.
See [`SPEC.md`](SPEC.md) for the full design and [`docs/CONTENT-SERVER-GUIDE.md`](docs/CONTENT-SERVER-GUIDE.md)
for content-server integration.

## Modules

| Module | Tech | Output |
|--------|------|--------|
| `common` | Java | shared DTOs + plugin-messaging/REST wire format |
| `auth-server` | Kotlin · Ktor · SQLite · DataGSM SDK | `auth-server-all.jar` (standalone web app) |
| `velocity-plugin` | Java · Velocity API | `velocity-plugin.jar` (proxy plugin) |
| `lobby-server` | Java · Minestom | `lobby-server-all.jar` (standalone server) |
| `content-lib` | Java · Paper API | `content-lib.jar` → the **SmpAuth** Paper plugin |
| `sample-content-plugin` | Java · Paper API | example content plugin |

## Build

Requires a **Java 25** toolchain (the build auto-detects `/opt/homebrew/opt/openjdk@25`; adjust
`org.gradle.java.installations.paths` in `gradle.properties` if yours lives elsewhere). Gradle runs
fine on JDK 25 or 26.

```bash
./gradlew build          # everything
./gradlew :auth-server:shadowJar :velocity-plugin:shadowJar :lobby-server:shadowJar :content-lib:shadowJar
```

## Run

### 1. auth-server
Register a client at <https://www.datagsm.kr/clients>, set its redirect URI to `<publicBaseUrl>/callback`,
then copy `auth-server/config.properties.example` → `config.properties` (or use `SMP_AUTH_*` env vars):

```bash
cd auth-server
SMP_AUTH_DATAGSM_CLIENT_ID=… SMP_AUTH_DATAGSM_CLIENT_SECRET=… \
SMP_AUTH_SHARED_SECRET=… SMP_AUTH_PUBLIC_BASE_URL=https://auth.example.kr \
java -jar build/libs/auth-server-all.jar
```

### 2. Velocity proxy
Drop `velocity-plugin.jar` into the proxy's `plugins/`, start once to generate
`plugins/smp-auth/config.properties`, then set `authServerBaseUrl`, `sharedSecret` (same as the
auth-server), and `lobbyServerName`. Enable **modern forwarding** in `velocity.toml` and share its
secret with the lobby.

### 3. lobby-server
First run writes `config.properties`; set `velocitySecret` (= Velocity's forwarding secret),
`authServerBaseUrl`, `authLoginUrl` (`<publicBaseUrl>/login`), and `sharedSecret`.

```bash
cd lobby-server && java -jar build/libs/lobby-server-all.jar
```

### 4. Content servers
Install `content-lib.jar` (SmpAuth) on each Paper content server. See the content-server guide.

## Flow

`/login` (lobby) → open URL → DataGSM OAuth → 8-char key shown → `/verify <key>` (lobby) →
auth-server binds `uuid→student` → Velocity loads it → content servers read it via `SmpAuth.get(player)`.
Velocity blocks transfer to any non-lobby server until the player is linked.
