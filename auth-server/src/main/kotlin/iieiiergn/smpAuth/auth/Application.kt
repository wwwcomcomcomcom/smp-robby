package iieiiergn.smpAuth.auth

import iieiiergn.smpAuth.common.RestDtos
import iieiiergn.smpAuth.common.StudentData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.application.ApplicationCall
import org.slf4j.LoggerFactory
import team.themoment.datagsm.sdk.oauth.DataGsmOAuthClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("smp-auth")

/** Nicknames must be 2-6 characters: Korean syllables, Latin letters, digits, `_`/`-`. */
private val NICKNAME_PATTERN = Regex("^[A-Za-z0-9가-힣_-]{2,6}$")

fun main() {
    val cfg = Config.load()

    val keyStore = KeyStore(cfg.keyLength, cfg.keyTtlSeconds)
    val pending = PendingStore()
    val nicknamePending = NicknamePendingStore()
    val repo = LinkRepository(cfg.dbPath)
    val nicknames = NicknameRepository(cfg.dbPath)
    val oauth = DataGsmOAuthClient.builder(cfg.datagsmClientId, cfg.datagsmClientSecret)
        .authorizationBaseUrl(cfg.authorizationBaseUrl)
        .userInfoBaseUrl(cfg.userInfoBaseUrl)
        .build()

    // Periodic eviction of expired keys / pending OAuth + nickname sessions.
    Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "smp-auth-evict").apply { isDaemon = true } }
        .scheduleAtFixedRate(
            { keyStore.evictExpired(); pending.evictExpired(); nicknamePending.evictExpired() },
            1, 1, TimeUnit.MINUTES,
        )

    log.info("Starting auth-server on port {} (public base {})", cfg.port, cfg.publicBaseUrl)
    embeddedServer(Netty, port = cfg.port) {
        module(cfg, oauth, keyStore, pending, repo, nicknames, nicknamePending)
    }.start(wait = true)
}

fun Application.module(
    cfg: Config,
    oauth: DataGsmOAuthClient,
    keyStore: KeyStore,
    pending: PendingStore,
    repo: LinkRepository,
    nicknames: NicknameRepository,
    nicknamePending: NicknamePendingStore,
) {
    install(ContentNegotiation) { gson() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }

    routing {
        // ---- Browser-facing OAuth flow ----
        get("/login") {
            val urlBuilder = oauth.createAuthorizationUrl(cfg.redirectUri)
                .scope(cfg.scope)
                .enablePkce()
            val state = pending.create(urlBuilder.codeVerifier)
            urlBuilder.state(state)
            call.respondRedirect(urlBuilder.build())
        }

        get("/callback") {
            val code = call.request.queryParameters["code"]
            val state = call.request.queryParameters["state"]
            if (code.isNullOrBlank()) {
                call.respondText(errorPage("Missing authorization code."), ContentType.Text.Html, HttpStatusCode.BadRequest)
                return@get
            }
            val verifier = pending.consume(state)
            if (verifier == null) {
                call.respondText(errorPage("Login session expired. Please run /login again."), ContentType.Text.Html, HttpStatusCode.BadRequest)
                return@get
            }
            val token = oauth.exchangeCodeForToken(code, cfg.redirectUri, verifier)
            val info = oauth.getUserInfo(token.accessToken)
            val student = StudentMapper.map(info)

            val existingNickname = nicknames.find(student.datagsmId())
            if (existingNickname != null) {
                issueKey(call, keyStore, cfg, student.withNickname(existingNickname))
            } else {
                val formToken = nicknamePending.create(student)
                call.respondText(nicknamePage(formToken), ContentType.Text.Html)
            }
        }

        // Submits the one-time nickname form shown after a first-ever OAuth login.
        post("/nickname") {
            val params = call.receiveParameters()
            val formToken = params["token"]
            val student = nicknamePending.consume(formToken)
            if (student == null) {
                call.respondText(
                    errorPage("입력 세션이 만료되었습니다. /login 으로 다시 시도하세요."),
                    ContentType.Text.Html, HttpStatusCode.BadRequest,
                )
                return@post
            }

            val nickname = params["nickname"]?.trim()
            if (nickname == null || !NICKNAME_PATTERN.matches(nickname)) {
                // Re-stash so the player can retry without redoing the whole OAuth round-trip.
                val retryToken = nicknamePending.create(student)
                call.respondText(
                    nicknamePage(retryToken, error = "닉네임은 한글/영문/숫자/-/_ 2~6자여야 합니다."),
                    ContentType.Text.Html, HttpStatusCode.BadRequest,
                )
                return@post
            }

            nicknames.save(student.datagsmId(), nickname)
            // Someone else may have won a concurrent first-save race — use whatever stuck.
            val savedNickname = nicknames.find(student.datagsmId()) ?: nickname
            issueKey(call, keyStore, cfg, student.withNickname(savedNickname))
        }

        // ---- Server-to-server REST (shared-secret protected) ----
        post("/api/bind") {
            if (!call.requireAuth(cfg.sharedSecret)) return@post
            val req = call.receive<RestDtos.BindRequest>()
            val student = keyStore.consume(req.key)
            if (student == null) {
                call.respond(HttpStatusCode.Gone, mapOf("error" to "invalid_or_expired_key"))
                return@post
            }
            repo.upsert(req.uuid, req.username, student)
            log.info("Bound uuid={} -> datagsmId={}", req.uuid, student.datagsmId())
            call.respond(RestDtos.BindResponse(student))
        }

        get("/api/links/{uuid}") {
            if (!call.requireAuth(cfg.sharedSecret)) return@get
            val uuid = call.parameters["uuid"]!!
            val student = repo.find(uuid)
            call.respond(RestDtos.LinkResponse(student != null, student))
        }
    }
}

private suspend fun issueKey(call: ApplicationCall, keyStore: KeyStore, cfg: Config, student: StudentData) {
    val key = keyStore.issue(student)
    log.info("Issued key for datagsmId={} ({}) nickname={}", student.datagsmId(), student.name(), student.nickname())
    call.respondText(keyPage(key, student, cfg.keyTtlSeconds), ContentType.Text.Html)
}

private suspend fun ApplicationCall.requireAuth(secret: String): Boolean {
    if (request.headers["Authorization"] != "Bearer $secret") {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
        return false
    }
    return true
}

private fun keyPage(key: String, student: StudentData, ttlSeconds: Long): String {
    val who = student.name() ?: student.email() ?: "your account"
    val minutes = ttlSeconds / 60
    return """
        <!doctype html><html lang="ko"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>SMP 인증</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;display:grid;place-items:center;height:100vh;margin:0}
          .card{background:#1e293b;padding:2.5rem 3rem;border-radius:16px;text-align:center;box-shadow:0 10px 40px rgba(0,0,0,.4)}
          .key{font-size:2.4rem;letter-spacing:.4rem;font-weight:700;margin:1rem 0;color:#38bdf8;font-family:monospace}
          .hint{color:#94a3b8;font-size:.9rem}
        </style></head><body>
        <div class="card">
          <h2>안녕하세요, ${esc(who)}님</h2>
          <p>아래 인증 키를 로비 서버에서 입력하세요:</p>
          <div class="key">${esc(key)}</div>
          <p class="hint">로비에서 <code>/verify ${esc(key)}</code> 를 입력하세요.<br>
          이 키는 ${minutes}분 후 만료되며 한 번만 사용할 수 있습니다.</p>
        </div></body></html>
    """.trimIndent()
}

private fun nicknamePage(token: String, error: String? = null): String {
    val errorHtml = if (error != null) """<p class="error">${esc(error)}</p>""" else ""
    return """
        <!doctype html><html lang="ko"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>SMP 인증 - 닉네임 설정</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;display:grid;place-items:center;height:100vh;margin:0}
          .card{background:#1e293b;padding:2.5rem 3rem;border-radius:16px;text-align:center;box-shadow:0 10px 40px rgba(0,0,0,.4)}
          input{font-size:1.2rem;padding:.6rem 1rem;border-radius:8px;border:none;margin:1rem 0;width:16rem;text-align:center;box-sizing:border-box}
          button{font-size:1.1rem;padding:.6rem 1.5rem;border-radius:8px;border:none;background:#38bdf8;color:#0f172a;font-weight:700;cursor:pointer}
          .hint{color:#94a3b8;font-size:.85rem}
          .error{color:#fca5a5;font-size:.9rem}
        </style></head><body>
        <div class="card">
          <h2>닉네임을 입력하세요</h2>
          <p class="hint">최초 로그인 시 한 번만 설정하며, 이후에는 변경할 수 없습니다.</p>
          $errorHtml
          <form method="post" action="/nickname">
            <input type="hidden" name="token" value="${esc(token)}">
            <br>
            <input type="text" name="nickname" minlength="2" maxlength="6" required autofocus placeholder="닉네임 (2~6자)">
            <br>
            <button type="submit">확인</button>
          </form>
        </div></body></html>
    """.trimIndent()
}

private fun errorPage(message: String): String = """
    <!doctype html><html lang="ko"><head><meta charset="utf-8"><title>SMP 인증 오류</title>
    <style>body{font-family:system-ui,sans-serif;background:#0f172a;color:#fca5a5;display:grid;place-items:center;height:100vh;margin:0}</style>
    </head><body><div><h2>인증 오류</h2><p>${esc(message)}</p></div></body></html>
""".trimIndent()

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
