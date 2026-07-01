package iieiiergn.smpAuth.auth

import iieiiergn.smpAuth.common.StudentData
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds a freshly OAuth-authenticated [StudentData] (nickname not yet set) while the browser
 * fills in the nickname form. Maps a one-time token — embedded as a hidden form field — back
 * to the student. Single-use, short-lived (5 min); mirrors [PendingStore].
 */
class NicknamePendingStore(private val ttlSeconds: Long = 300) {

    private val random = SecureRandom()
    private val entries = ConcurrentHashMap<String, Entry>()

    private data class Entry(val student: StudentData, val expiresAt: Long)

    /** Stash [student] and return a token the form can round-trip back to [consume]. */
    fun create(student: StudentData): String {
        val token = newToken()
        entries[token] = Entry(student, now() + ttlSeconds * 1000)
        return token
    }

    /** Consume the token, returning its student if valid and unexpired. Single-use. */
    fun consume(token: String?): StudentData? {
        if (token == null) return null
        val entry = entries.remove(token) ?: return null
        return if (entry.expiresAt >= now()) entry.student else null
    }

    fun evictExpired() {
        val cutoff = now()
        entries.entries.removeIf { it.value.expiresAt < cutoff }
    }

    private fun newToken(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun now() = System.currentTimeMillis()
}
