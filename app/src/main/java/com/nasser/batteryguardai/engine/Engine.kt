package com.nasser.batteryguardai.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.nasser.batteryguardai.MainActivity
import com.nasser.batteryguardai.R
import com.nasser.batteryguardai.data.*
import java.util.concurrent.TimeUnit
import kotlin.math.max

const val HOUR = 3_600_000L
const val DAY = 24 * HOUR

object BatteryReader {
    fun read(ctx: Context): BatterySample {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val screen = ctx.getSystemService(PowerManager::class.java).isInteractive
        return BatterySample(
            time = System.currentTimeMillis(), level = pct, charging = charging, tempC = temp, voltageMv = volt,
            currentUa = if (cur == Int.MIN_VALUE) 0 else cur,
            chargeCounterUah = if (cc == Int.MIN_VALUE || cc < 0) 0 else cc, screenOn = screen
        )
    }
}

object Analyzer {
    data class Rate(val perHour: Double, val confidence: Int)

    private fun pairs(l: List<BatterySample>) =
        l.zipWithNext().filter { (a, b) -> b.time - a.time in 60_000L..3 * HOUR }

    fun dischargeRate(l: List<BatterySample>, screenOffOnly: Boolean = false, screenOnOnly: Boolean = false): Rate? {
        var drop = 0.0; var hrs = 0.0
        for ((a, b) in pairs(l)) {
            if (a.charging || b.charging) continue
            if (screenOffOnly && (a.screenOn || b.screenOn)) continue
            if (screenOnOnly && !(a.screenOn && b.screenOn)) continue
            val d = a.level - b.level
            if (d < 0) continue
            drop += d; hrs += (b.time - a.time) / HOUR.toDouble()
        }
        if (hrs < 0.5) return null
        return Rate(drop / hrs, (25 + hrs * 4).toInt().coerceAtMost(95))
    }

    fun chargeRate(l: List<BatterySample>): Rate? {
        var gain = 0.0; var hrs = 0.0
        for ((a, b) in pairs(l)) {
            if (!a.charging || !b.charging) continue
            val d = b.level - a.level
            if (d < 0) continue
            gain += d; hrs += (b.time - a.time) / HOUR.toDouble()
        }
        if (hrs < 0.25) return null
        return Rate(gain / hrs, (25 + hrs * 8).toInt().coerceAtMost(90))
    }

    /** معدل التفريغ لكل ساعة من الساعات الـ24 التي تبدأ عند start: (مجموع الانخفاض، الساعات المغطاة). */
    fun hourlyDrain(l: List<BatterySample>, start: Long): Pair<DoubleArray, DoubleArray> {
        val drop = DoubleArray(24); val cov = DoubleArray(24)
        for ((a, b) in pairs(l)) {
            if (a.charging || b.charging || a.level < b.level) continue
            val idx = ((a.time - start) / HOUR).toInt()
            if (idx !in 0..23) continue
            drop[idx] += (a.level - b.level).toDouble(); cov[idx] += (b.time - a.time) / HOUR.toDouble()
        }
        return drop to cov
    }

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 3) return null
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in x.indices) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my) }
        if (sxx < 1e-9 || syy < 1e-9) return null
        return sxy / Math.sqrt(sxx * syy)
    }

    fun remainingHours(level: Int, r: Rate?): Double? = if (r != null && r.perHour > 0.05) level / r.perHour else null
    fun hoursToFull(level: Int, r: Rate?): Double? = if (r != null && r.perHour > 0.05) (100 - level) / r.perHour else null

    /** تقدير تقريبي للسعة الحالية بالمللي أمبير ساعة من عداد الشحن. ليس قياسًا دقيقًا لصحة البطارية. */
    fun capacityEstimateMah(l: List<BatterySample>): Int? {
        val v = l.filter { it.chargeCounterUah > 0 && it.level in 30..95 }
            .map { it.chargeCounterUah / 1000.0 / (it.level / 100.0) }.sorted()
        return if (v.size < 10) null else v[v.size / 2].toInt()
    }
}

object RecommendationEngine {
    fun generate(samples: List<BatterySample>, sessions: List<ChargingSession>, now: Long): List<Recommendation> {
        val out = mutableListOf<Recommendation>()
        val today = samples.filter { it.time > now - DAY }
        val base = samples.filter { it.time <= now - DAY && it.time > now - 8 * DAY }
        val rT = Analyzer.dischargeRate(today); val rB = Analyzer.dischargeRate(base)
        if (rT != null && rB != null && rB.perHour > 0.3 && rT.perHour > rB.perHour * 1.2 && rT.confidence >= 40 && rB.confidence >= 40) {
            val pct = ((rT.perHour / rB.perHour - 1) * 100).toInt()
            val screenOn = Analyzer.dischargeRate(today, screenOnOnly = true)
            val screenShare = today.count { it.screenOn } * 100 / max(1, today.size)
            out += Recommendation(time = now, title = "استهلاك أعلى من المعتاد",
                problem = "استهلاك البطارية خلال آخر 24 ساعة أعلى من متوسطك المعتاد بنسبة $pct%.",
                evidence = "المعدل الحالي ${f1(rT.perHour)}% في الساعة مقابل ${f1(rB.perHour)}% في الأيام السابقة. الشاشة كانت مشغّلة في نحو $screenShare% من القياسات" +
                    (screenOn?.let { "، ومعدل التفريغ والشاشة تعمل ${f1(it.perHour)}%/س" } ?: "") + ".",
                cause = "زيادة محتملة في وقت الشاشة أو السطوع أو النشاط الشبكي. لا تتوفر بيانات تحدد تطبيقًا بعينه.",
                action = "خفّض السطوع، استخدم Wi-Fi عند توفره، وراجع قسم مرصد استهلاك التطبيقات لمعرفة أكثر التطبيقات استخدامًا.",
                confidence = minOf(rT.confidence, rB.confidence))
        }
        Analyzer.dischargeRate(today, screenOffOnly = true)?.let { idle ->
            if (idle.perHour > 2.0 && idle.confidence >= 40) out += Recommendation(time = now,
                title = "استنزاف أثناء عدم الاستخدام",
                problem = "البطارية تنخفض أثناء إطفاء الشاشة بمعدل أعلى من المعتاد.",
                evidence = "معدل التفريغ والشاشة مطفأة ${f1(idle.perHour)}% في الساعة (الطبيعي غالبًا أقل من 1–2%).",
                cause = "قد يكون بسبب نشاط خلفي أو اتصال ضعيف بالشبكة أو مزامنة. السبب غير محدد بدقة.",
                action = "راجع إعدادات إدارة التطبيقات والبطارية، وقيّد نشاط الخلفية للتطبيقات غير الضرورية.",
                confidence = idle.confidence)
        }
        val maxT = today.maxOfOrNull { it.tempC } ?: 0f
        if (maxT >= 40f) out += Recommendation(time = now, title = "ارتفاع في حرارة البطارية",
            problem = "سُجلت درجة حرارة مرتفعة للبطارية.",
            evidence = "أعلى قراءة خلال 24 ساعة: ${f1(maxT.toDouble())}°م.",
            cause = "قد ينتج عن الشحن السريع أو الاستخدام الثقيل أثناء الشحن أو التعرض للحرارة.",
            action = "تجنب الاستخدام الثقيل أثناء الشحن، وأخرج الهاتف من الغطاء أو الشمس.",
            confidence = 85)
        val night = sessions.filter { s -> s.endTime != null && s.endTime - s.startTime >= 6 * HOUR && (s.endLevel ?: 0) >= 95 }
        if (night.size >= 2) out += Recommendation(time = now, title = "شحن ليلي طويل متكرر",
            problem = "جلسات شحن طويلة تبقي البطارية عند 100% لساعات.",
            evidence = "${night.size} جلسات حديثة تجاوزت 6 ساعات وانتهت قرب 100%.",
            cause = "إبقاء البطارية ممتلئة لفترات طويلة يزيد الإجهاد عليها مع الزمن.",
            action = "فعّل «حماية البطارية» من إعدادات سامسونج (حد 80–85%) أو استخدم تنبيه نسبة الشحن.",
            confidence = 70)
        return out
    }
}

fun f1(d: Double): String = String.format(java.util.Locale.US, "%.1f", d)

object Notifier {
    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alerts", ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("analysis", ctx.getString(R.string.channel_analysis), NotificationManager.IMPORTANCE_DEFAULT))
    }

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun send(ctx: Context, channel: String, text: String) {
        val p = Prefs(ctx)
        val title = ctx.getString(R.string.notif_title)
        AppDb.get(ctx).dao().insertNotif(NotificationRecord(time = System.currentTimeMillis(), title = title, text = text))
        if (channel == "alerts" && !p.notifAlerts) return
        if (channel == "analysis" && !p.notifAnalysis) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify((System.nanoTime() % Int.MAX_VALUE).toInt(), n)
    }
}

object Automation {
    val triggers = linkedMapOf(
        "LEVEL_BELOW" to "عند انخفاض البطارية إلى (%) أو أقل",
        "CHARGE_REACHED" to "عند بلوغ نسبة الشحن (%) أثناء الشحن",
        "TEMP_ABOVE" to "عند ارتفاع الحرارة إلى (°م) أو أكثر",
        "CHARGE_STARTED" to "عند بدء الشحن",
        "CHARGE_ENDED" to "عند انتهاء الشحن / فصل الشاحن"
    )

    suspend fun run(ctx: Context, dao: BgDao, s: BatterySample, prev: BatterySample?) {
        for (r in dao.enabledRules()) {
            val (hit, msg) = when (r.trigger) {
                "LEVEL_BELOW" -> (!s.charging && s.level <= r.threshold) to "انخفضت البطارية إلى ${s.level}%."
                "CHARGE_REACHED" -> (s.charging && s.level >= r.threshold) to "وصلت البطارية إلى نسبة الشحن التي حددتها (${s.level}%)."
                "TEMP_ABOVE" -> (s.tempC >= r.threshold) to "درجة حرارة البطارية مرتفعة (${f1(s.tempC.toDouble())}°م)، يُرجى الانتباه."
                "CHARGE_STARTED" -> (s.charging && prev != null && !prev.charging) to "بدأت جلسة شحن جديدة عند ${s.level}%."
                "CHARGE_ENDED" -> (!s.charging && prev?.charging == true) to "انتهت جلسة الشحن عند ${prev?.level}%."
                else -> false to ""
            }
            if (!hit || s.time - r.lastFired < r.cooldownMin * 60_000L) continue
            if (!inWindow(r, s.time)) continue
            Notifier.send(ctx, "alerts", msg)
            dao.updateRule(r.copy(lastFired = s.time))
            dao.insertLog(AutomationLog(time = s.time, ruleName = r.name, message = msg))
        }
    }

    fun inWindow(r: AutomationRule, time: Long): Boolean {
        if (r.startHour == 0 && r.endHour == 24) return true
        val h = java.util.Calendar.getInstance().apply { timeInMillis = time }.get(java.util.Calendar.HOUR_OF_DAY)
        return if (r.startHour < r.endHour) h in r.startHour until r.endHour else h >= r.startHour || h < r.endHour
    }

    suspend fun seedDefaults(dao: BgDao) {
        if (dao.ruleCount() > 0) return
        dao.insertRule(AutomationRule(name = "تنبيه البطارية المنخفضة", trigger = "LEVEL_BELOW", threshold = 20, cooldownMin = 120))
        dao.insertRule(AutomationRule(name = "تنبيه الوصول إلى 80%", trigger = "CHARGE_REACHED", threshold = 80, cooldownMin = 240))
        dao.insertRule(AutomationRule(name = "تنبيه الحرارة", trigger = "TEMP_ABOVE", threshold = 42, cooldownMin = 60))
    }
}

class SampleWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val dao = AppDb.get(c).dao()
        val s = BatteryReader.read(c)
        if (s.level < 0) return Result.success()
        val prev = dao.lastSample()
        dao.insertSample(s)
        updateSessions(dao, s)
        Automation.seedDefaults(dao)
        val recent = dao.samplesSince(s.time - 8 * DAY)
        val recs = RecommendationEngine.generate(recent, dao.recentSessions(20), s.time)
        val last = dao.recommendationsSince(s.time - 6 * HOUR)
        for (r in recs.filter { n -> last.none { it.title == n.title } }) {
            dao.insertRecommendation(r)
            Notifier.send(c, "analysis", r.problem)
        }
        monthlyReport(dao, s.time, c)
        Automation.run(c, dao, s, prev)
        dao.deleteSamplesBefore(s.time - 90 * DAY)
        return Result.success()
    }

    private suspend fun monthlyReport(dao: BgDao, now: Long, c: Context) {
        val month = dao.samplesSince(now - 30 * DAY)
        if (month.isEmpty() || now - month.first().time < 7 * DAY) return
        if (dao.recommendationsSince(now - 30 * DAY).any { it.title == "التقرير الشهري" }) return
        val rate = Analyzer.dischargeRate(month)
        val cap = Analyzer.capacityEstimateMah(month)
        val sess = dao.recentSessions(100).filter { it.startTime > now - 30 * DAY }
        val r = Recommendation(time = now, title = "التقرير الشهري",
            problem = "أصبحت لديك بيانات كافية لإنشاء تقرير صحي للبطارية.",
            evidence = "متوسط التفريغ ${rate?.let { f1(it.perHour) } ?: "؟"}%/س، أعلى حرارة ${f1(month.maxOf { it.tempC }.toDouble())}°م، ${sess.size} جلسة شحن، " +
                "تقدير السعة ${cap?.let { "≈ $it مللي أمبير ساعة (تقدير)" } ?: "غير متاح"}.",
            cause = "ملخص آخر 30 يومًا من القياسات المحلية. التقدير ليس نسبة صحة دقيقة.",
            action = "قارن هذه القيم بالتقرير القادم؛ إن انخفض تقدير السعة باستمرار فراجع عادات الشحن.",
            confidence = rate?.confidence ?: 40)
        dao.insertRecommendation(r)
        Notifier.send(c, "analysis", r.problem)
    }

    private suspend fun updateSessions(dao: BgDao, s: BatterySample) {
        val open = dao.openSession()
        if (s.charging) {
            if (open == null) dao.insertSession(ChargingSession(startTime = s.time, startLevel = s.level, maxTempC = s.tempC))
            else dao.updateSession(open.copy(maxTempC = max(open.maxTempC, s.tempC), endLevel = s.level))
        } else if (open != null) {
            dao.updateSession(open.copy(endTime = s.time, endLevel = open.endLevel ?: s.level, maxTempC = max(open.maxTempC, s.tempC)))
        }
    }
}

object Scheduler {
    fun intervalMin(ctx: Context): Long {
        val p = Prefs(ctx)
        if (p.lowPower) return 60
        return when (p.mode) { 0 -> 30; 4 -> p.customInterval.toLong(); else -> 15 }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SampleWorker>(intervalMin(ctx), TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sampler", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<SampleWorker>().build())
    }
}
