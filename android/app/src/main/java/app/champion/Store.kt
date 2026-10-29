package app.champion

import android.app.Application
import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class ChampionApplication : Application() {
    lateinit var store: EventStore
        private set
    lateinit var appearance: AppearanceStore
        private set
    lateinit var health: BackgroundHealth
        private set
    val engine by lazy { BackgroundEngine(this) }
    override fun onCreate() {
        super.onCreate()
        store = EventStore(this)
        appearance = AppearanceStore(this)
        health = BackgroundHealth(this)
    }
}

data class RuntimeState(
    val running: Boolean = false,
    val lastSync: Long = 0,
    val syncError: String = "",
    val collectionError: String = "",
    val locationPlace: String = "等待定位",
    val locationAccuracy: Float? = null,
    val locationAt: Long = 0L,
    val locationMessage: String = "",
    val backgroundMessage: String = "",
    val lastCollection: Long = 0L,
    val backgroundLog: String = "",
)
object LiveState {
    val value = MutableStateFlow(RuntimeState())
    @Volatile var foreground = false
}

class EventStore(context: Context) : SQLiteOpenHelper(context, "moments.db", null, 1) {
    val revision = MutableStateFlow(0L)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,owner TEXT NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,pending INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX by_time ON events(at DESC)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun append(moment: Moment) {
        insert(writableDatabase, moment, true)
        revision.value++
    }

    private fun insert(db: SQLiteDatabase, m: Moment, pending: Boolean): Boolean =
        db.insertWithOnConflict("events", null, ContentValues().apply {
            put("id", m.id); put("owner", m.owner); put("at", m.at); put("kind", m.kind.name)
            put("payload", m.json().toString()); put("pending", if (pending) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    @Synchronized fun pending(): List<Moment> = buildList {
        readableDatabase.rawQuery("SELECT payload FROM events WHERE pending=1 AND kind!='PRESENCE' ORDER BY at LIMIT 100", null)
            .use { c -> while (c.moveToNext()) add(Moment.parse(JSONObject(c.getString(0)))) }
        readableDatabase.rawQuery("SELECT payload FROM events WHERE pending=1 AND kind='PRESENCE' ORDER BY at DESC LIMIT 1", null)
            .use { c -> if (c.moveToFirst()) add(Moment.parse(JSONObject(c.getString(0)))) }
    }

    @Synchronized fun acknowledge(ids: List<String>, presenceUpTo: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("UPDATE events SET pending=0 WHERE id=?", arrayOf(it)) }
            presenceUpTo?.let { db.execSQL("UPDATE events SET pending=0 WHERE kind='PRESENCE' AND at<=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun receive(moments: List<Moment>, role: String, archive: Int? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            moments.forEach { insert(db, it, false) }
            archive?.let { db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", arrayOf("archive.$role", it.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
    }

    @Synchronized fun archiveCursor(role: String): Int = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf("archive.$role"))
        .use { if (it.moveToFirst()) it.getString(0).toInt() else 0 }

    @Synchronized fun prepareServer(role: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val prepared = db.rawQuery("SELECT value FROM meta WHERE key='aliyun.origin'", null)
                .use { it.moveToFirst() && it.getString(0) == BuildConfig.SYNC_ORIGIN }
            if (!prepared) {
                db.execSQL("UPDATE events SET pending=1 WHERE owner=?", arrayOf(role))
                db.execSQL("DELETE FROM meta WHERE key IN ('aliyun.headSeen','aliyun.archiveCursor')")
                db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES('aliyun.origin',?)", arrayOf(BuildConfig.SYNC_ORIGIN))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun cloudProgress(key: String): Long = readableDatabase.rawQuery(
        "SELECT value FROM meta WHERE key=?", arrayOf("aliyun.$key")
    ).use { if (it.moveToFirst()) it.getString(0).toLong() else 0L }

    @Synchronized fun commitCloudSync(ids: List<String>, presenceUpTo: Long?, moments: List<Moment>, headSeen: Long, archiveCursor: Long): List<Moment> {
        val db = writableDatabase
        val fresh = mutableListOf<Moment>()
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("UPDATE events SET pending=0 WHERE id=?", arrayOf(it)) }
            presenceUpTo?.let { db.execSQL("UPDATE events SET pending=0 WHERE kind='PRESENCE' AND at<=?", arrayOf(it)) }
            moments.forEach { if (insert(db, it, false)) fresh.add(it) }
            for ((key, value) in listOf("headSeen" to headSeen, "archiveCursor" to archiveCursor)) {
                db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", arrayOf("aliyun.$key", value.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
        return fresh
    }

    @Synchronized fun hasPendingActivity(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM events WHERE pending=1 AND kind!='PRESENCE' LIMIT 1", null
    ).use { it.moveToFirst() }

    @Synchronized fun history(limit: Int): List<Moment> = readableDatabase.rawQuery(
        "SELECT payload FROM events WHERE kind!='PRESENCE' ORDER BY at DESC,rowid DESC", null
    ).use { c ->
        val timeline = Timeline()
        buildList {
            // Filter before limiting: old launcher spam must not push real events off the page.
            while (size < limit && c.moveToNext()) {
                val moment = Moment.parse(JSONObject(c.getString(0)))
                if (timeline.accept(moment)) add(moment)
            }
        }
    }

    @Synchronized fun presence(owner: String): Moment? = readableDatabase.rawQuery(
        "SELECT payload FROM events WHERE owner=? AND kind='PRESENCE' ORDER BY at DESC LIMIT 1", arrayOf(owner)
    ).use { if (it.moveToFirst()) Moment.parse(JSONObject(it.getString(0))) else null }

    @Synchronized fun locationAnchor(owner: String): Moment? {
        val saved = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf("location.anchor.$owner"))
            .use { if (it.moveToFirst()) Moment.parse(JSONObject(it.getString(0))) else null }
        if (saved != null) return saved
        // Upgrade from the last own position; never use the partner's position as this phone's baseline.
        val previous = readableDatabase.rawQuery(
            "SELECT payload FROM events WHERE owner=? AND kind='LOCATION' ORDER BY at DESC LIMIT 1", arrayOf(owner)
        ).use { if (it.moveToFirst()) Moment.parse(JSONObject(it.getString(0))) else null }
        if (previous?.latitude != null && previous.longitude != null) saveLocationAnchor(previous, false)
        return previous?.takeIf { it.latitude != null && it.longitude != null }
    }

    @Synchronized fun saveLocationAnchor(moment: Moment, publish: Boolean) {
        require(moment.kind == Kind.LOCATION && moment.latitude != null && moment.longitude != null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (publish) insert(db, moment, true)
            db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",
                arrayOf("location.anchor.${moment.owner}", moment.json().toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (publish) revision.value++
    }

    @Synchronized fun historyCount(before: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE pending=0 AND kind!='PRESENCE' AND at<?", arrayOf(before.toString())
    ).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized fun clearHistory(before: Long): CleanupResult {
        val db = writableDatabase
        // Pending uploads, device presence, sync cursors and appearance are kept.
        val count = db.delete("events", "pending=0 AND kind!='PRESENCE' AND at<?", arrayOf(before.toString()))
        revision.value++
        val compacted = try { db.execSQL("VACUUM"); true }
            catch (_: android.database.SQLException) { false }
        return CleanupResult(count, compacted)
    }

    @Synchronized fun prune() {
        // Chat history is cleared manually. Only obsolete sent heartbeats expire automatically.
        val now = System.currentTimeMillis()
        writableDatabase.delete("events", "pending=0 AND kind='PRESENCE' AND at<?", arrayOf((now - 86_400_000L).toString()))
    }
}

data class CleanupResult(val count: Int, val compacted: Boolean)
