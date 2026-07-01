package iieiiergn.smpAuth.auth

import java.sql.Connection
import java.sql.DriverManager

/**
 * Persistent {@code datagsm_id -> nickname} store backed by SQLite.
 * Keyed by DataGSM identity (not Minecraft UUID) because the nickname is collected right
 * after OAuth, before the player has pasted their key and bound a Minecraft account.
 * Nicknames are set once and never overwritten — see [save].
 */
class NicknameRepository(dbPath: String) {

    private val lock = Any()
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        synchronized(lock) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS nicknames (
                        datagsm_id INTEGER PRIMARY KEY,
                        nickname   TEXT NOT NULL,
                        set_at     INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    /** The persisted nickname for [datagsmId], or null if they haven't set one yet. */
    fun find(datagsmId: Long): String? {
        synchronized(lock) {
            conn.prepareStatement("SELECT nickname FROM nicknames WHERE datagsm_id = ?").use { ps ->
                ps.setLong(1, datagsmId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString(1) else null
                }
            }
        }
    }

    /**
     * Persist [nickname] for [datagsmId] if none is set yet. A concurrent first-login race is
     * resolved by first-write-wins; callers should re-[find] after calling this to get the
     * value that actually stuck.
     */
    fun save(datagsmId: Long, nickname: String) {
        synchronized(lock) {
            conn.prepareStatement(
                """
                INSERT INTO nicknames (datagsm_id, nickname, set_at) VALUES (?, ?, ?)
                ON CONFLICT(datagsm_id) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, datagsmId)
                ps.setString(2, nickname)
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }
}
