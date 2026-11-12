package com.nasser.batteryguardai.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "battery_samples", indices = [Index("time")])
data class BatterySample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val level: Int,
    val charging: Boolean,
    val tempC: Float,
    val voltageMv: Int,
    val currentUa: Int,          // 0 = غير متاح
    val chargeCounterUah: Int,   // 0 = غير متاح
    val screenOn: Boolean
)

@Entity(tableName = "charging_sessions")
data class ChargingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val startLevel: Int,
    val endLevel: Int? = null,
    val maxTempC: Float = 0f
)

@Entity(tableName = "recommendations")
data class Recommendation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val title: String,
    val problem: String,
    val evidence: String,
    val cause: String,
    val action: String,
    val confidence: Int
)

@Entity(tableName = "automation_rules")
data class AutomationRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val trigger: String,
    val threshold: Int,
    val enabled: Boolean = true,
    val lastFired: Long = 0,
    val cooldownMin: Int = 120,
    @ColumnInfo(defaultValue = "0") val startHour: Int = 0,
    @ColumnInfo(defaultValue = "24") val endHour: Int = 24
)

@Entity(tableName = "automation_logs")
data class AutomationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long, val ruleName: String, val message: String
)

@Entity(tableName = "notifications_history")
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long, val title: String, val text: String
)

@Dao
interface BgDao {
    @Insert suspend fun insertSample(s: BatterySample)
    @Query("SELECT * FROM battery_samples WHERE time >= :since ORDER BY time ASC")
    suspend fun samplesSince(since: Long): List<BatterySample>
    @Query("SELECT * FROM battery_samples WHERE time >= :since ORDER BY time ASC")
    fun samplesFlow(since: Long): Flow<List<BatterySample>>
    @Query("SELECT * FROM battery_samples ORDER BY time DESC LIMIT 1")
    suspend fun lastSample(): BatterySample?
    @Query("DELETE FROM battery_samples WHERE time < :t") suspend fun deleteSamplesBefore(t: Long)

    @Insert suspend fun insertSession(s: ChargingSession): Long
    @Update suspend fun updateSession(s: ChargingSession)
    @Query("SELECT * FROM charging_sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun openSession(): ChargingSession?
    @Query("SELECT * FROM charging_sessions ORDER BY startTime DESC LIMIT :n")
    fun sessionsFlow(n: Int): Flow<List<ChargingSession>>
    @Query("SELECT * FROM charging_sessions ORDER BY startTime DESC LIMIT :n")
    suspend fun recentSessions(n: Int): List<ChargingSession>

    @Insert suspend fun insertRecommendation(r: Recommendation)
    @Query("SELECT * FROM recommendations ORDER BY time DESC LIMIT 50")
    fun recommendationsFlow(): Flow<List<Recommendation>>
    @Query("SELECT * FROM recommendations WHERE time >= :since")
    suspend fun recommendationsSince(since: Long): List<Recommendation>

    @Insert suspend fun insertRule(r: AutomationRule)
    @Update suspend fun updateRule(r: AutomationRule)
    @Delete suspend fun deleteRule(r: AutomationRule)
    @Query("SELECT * FROM automation_rules ORDER BY id") fun rulesFlow(): Flow<List<AutomationRule>>
    @Query("SELECT * FROM automation_rules WHERE enabled = 1") suspend fun enabledRules(): List<AutomationRule>
    @Query("SELECT COUNT(*) FROM automation_rules") suspend fun ruleCount(): Int

    @Insert suspend fun insertLog(l: AutomationLog)
    @Query("SELECT * FROM automation_logs ORDER BY time DESC LIMIT 100") fun logsFlow(): Flow<List<AutomationLog>>
    @Insert suspend fun insertNotif(n: NotificationRecord)
    @Query("SELECT * FROM notifications_history ORDER BY time DESC LIMIT 100") fun notifsFlow(): Flow<List<NotificationRecord>>
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE automation_rules ADD COLUMN startHour INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE automation_rules ADD COLUMN endHour INTEGER NOT NULL DEFAULT 24")
    }
}

@Database(
    entities = [BatterySample::class, ChargingSession::class, Recommendation::class,
        AutomationRule::class, AutomationLog::class, NotificationRecord::class],
    version = 2, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): BgDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "batteryguard.db")
                .addMigrations(MIGRATION_1_2).build().also { inst = it }
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("bg_prefs", Context.MODE_PRIVATE)

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var lowPower: Boolean
        get() = sp.getBoolean("low", false)
        set(v) { sp.edit().putBoolean("low", v).apply() }

    var notifAlerts: Boolean
        get() = sp.getBoolean("n_alerts", true)
        set(v) { sp.edit().putBoolean("n_alerts", v).apply() }

    var notifAnalysis: Boolean
        get() = sp.getBoolean("n_analysis", true)
        set(v) { sp.edit().putBoolean("n_analysis", v).apply() }

    var mode: Int
        get() = sp.getInt("mode", 3)
        set(v) { sp.edit().putInt("mode", v).apply() }

    var customInterval: Int
        get() = sp.getInt("c_int", 15)
        set(v) { sp.edit().putInt("c_int", v).apply() }
}
