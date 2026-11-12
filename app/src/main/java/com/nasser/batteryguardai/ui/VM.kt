package com.nasser.batteryguardai.ui

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasser.batteryguardai.data.*
import com.nasser.batteryguardai.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class VM(private val app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    val prefs = Prefs(app)

    val live = MutableStateFlow(BatteryReader.read(app))
    private fun <T> Flow<T>.st(init: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)
    val samples = dao.samplesFlow(System.currentTimeMillis() - 30 * DAY).st(emptyList())
    val sessions = dao.sessionsFlow(30).st(emptyList())
    val recs = dao.recommendationsFlow().st(emptyList())
    val rules = dao.rulesFlow().st(emptyList())
    val logs = dao.logsFlow().st(emptyList())
    val notifs = dao.notifsFlow().st(emptyList())

    var themeMode by mutableStateOf(prefs.theme)
    var lowPower by mutableStateOf(prefs.lowPower)
    var notifAlerts by mutableStateOf(prefs.notifAlerts)
    var notifAnalysis by mutableStateOf(prefs.notifAnalysis)
    var mode by mutableStateOf(prefs.mode)
    var customInterval by mutableStateOf(prefs.customInterval)
    var topApps by mutableStateOf<List<Pair<String, Long>>>(emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) { Automation.seedDefaults(dao) }
        Scheduler.runNow(app)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { live.value = BatteryReader.read(app) }
    }
    private var registered = false
    fun startLive() {
        if (registered) return
        live.value = BatteryReader.read(app)
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)); registered = true
    }
    fun stopLive() { if (registered) { app.unregisterReceiver(receiver); registered = false } }
    override fun onCleared() { stopLive() }

    fun setTheme(v: Int) { themeMode = v; prefs.theme = v }
    fun setLow(v: Boolean) { lowPower = v; prefs.lowPower = v; Scheduler.schedule(app) }
    fun setAlerts(v: Boolean) { notifAlerts = v; prefs.notifAlerts = v }
    fun setAnalysis(v: Boolean) { notifAnalysis = v; prefs.notifAnalysis = v }
    fun updateMode(v: Int) { mode = v; prefs.mode = v; Scheduler.schedule(app) }
    fun updateCustomInterval(v: Int) { customInterval = v; prefs.customInterval = v; Scheduler.schedule(app) }
    fun analyzeNow() = Scheduler.runNow(app)

    fun addRule(name: String, trigger: String, th: Int, from: Int, to: Int) = viewModelScope.launch(Dispatchers.IO) {
        dao.insertRule(AutomationRule(name = name, trigger = trigger, threshold = th, startHour = from.coerceIn(0, 23), endHour = to.coerceIn(1, 24)))
    }
    fun toggleRule(r: AutomationRule) = viewModelScope.launch(Dispatchers.IO) { dao.updateRule(r.copy(enabled = !r.enabled)) }
    fun deleteRule(r: AutomationRule) = viewModelScope.launch(Dispatchers.IO) { dao.deleteRule(r) }
    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) { AppDb.get(app).clearAllTables(); Automation.seedDefaults(dao) }

    fun exportCsv(): Intent {
        val sb = StringBuilder("time,level,charging,temp_c,voltage_mv,current_ua,charge_counter_uah,screen_on\n")
        samples.value.forEach { sb.append("${it.time},${it.level},${it.charging},${it.tempC},${it.voltageMv},${it.currentUa},${it.chargeCounterUah},${it.screenOn}\n") }
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "batteryguard.csv").apply { writeText(sb.toString()) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "تصدير سجل البطارية")
    }

    fun exportPdf(): Intent {
        val now = System.currentTimeMillis()
        val all = samples.value
        val day = all.filter { it.time > now - DAY }
        val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
        val lines = mutableListOf("تقرير حارس البطارية الذكي", fmt.format(java.util.Date(now)), "",
            "عدد القياسات: ${all.size}",
            "معدل التفريغ (24 ساعة): ${Analyzer.dischargeRate(day)?.let { f1(it.perHour) + "% / س" } ?: "غير متاح"}",
            "معدل التفريغ (الأسبوع): ${Analyzer.dischargeRate(all.filter { it.time > now - 7 * DAY })?.let { f1(it.perHour) + "% / س" } ?: "غير متاح"}",
            "أعلى حرارة (24 ساعة): ${day.maxOfOrNull { it.tempC }?.let { f1(it.toDouble()) + "°م" } ?: "غير متاح"}",
            "تقدير السعة: ${Analyzer.capacityEstimateMah(all)?.let { "≈ $it مللي أمبير ساعة (تقدير)" } ?: "غير متاح"}", "", "جلسات الشحن الأخيرة:")
        sessions.value.take(10).forEach { lines += "${fmt.format(java.util.Date(it.startTime))}: ${it.startLevel}% ← ${it.endLevel ?: "؟"}%  (أعلى حرارة ${f1(it.maxTempC.toDouble())}°م)" }
        lines += ""; lines += "التوصيات:"
        recs.value.take(8).forEach { lines += "- ${it.title}: ${it.problem} | الدليل: ${it.evidence} | الإجراء: ${it.action}" }
        val doc = android.graphics.pdf.PdfDocument()
        val paint = android.graphics.Paint().apply { textSize = 11f; textAlign = android.graphics.Paint.Align.RIGHT; isAntiAlias = true }
        var pageNo = 1; var page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); var y = 50f
        for (raw in lines) {
            val wrapped = raw.chunked(70).ifEmpty { listOf("") }
            for (ln in wrapped) {
                if (y > 800f) {
                    doc.finishPage(page); pageNo++
                    page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); y = 50f
                }
                page.canvas.drawText(ln, 545f, y, paint); y += 17f
            }
        }
        doc.finishPage(page)
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "batteryguard-report.pdf")
        f.outputStream().use { doc.writeTo(it) }; doc.close()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "تصدير تقرير PDF")
    }

    var corr by mutableStateOf<List<Triple<String, Double, Double>>>(emptyList())

    /** يربط دقائق استخدام كل تطبيق في كل ساعة بمعدل التفريغ في الساعة نفسها (ارتباط لا يثبت سببًا). */
    fun loadCorrelation() = viewModelScope.launch(Dispatchers.IO) {
        if (!hasUsageAccess()) { corr = emptyList(); return@launch }
        val end = System.currentTimeMillis(); val start = end - DAY
        val usm = app.getSystemService(UsageStatsManager::class.java)
        val ev = usm.queryEvents(start, end); val e = android.app.usage.UsageEvents.Event()
        val open = HashMap<String, Long>(); val per = HashMap<String, DoubleArray>()
        fun add(pkg: String, a: Long, b: Long) {
            val arr = per.getOrPut(pkg) { DoubleArray(24) }; var t = a
            while (t < b) {
                val idx = ((t - start) / HOUR).toInt().coerceIn(0, 23)
                val seg = minOf(b, start + (idx + 1) * HOUR) - t
                if (seg <= 0) break
                arr[idx] += seg / 60000.0; t += seg
            }
        }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> open[e.packageName] = e.timeStamp
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> open.remove(e.packageName)?.let { add(e.packageName, it, e.timeStamp) }
            }
        }
        open.forEach { (p, s) -> add(p, s, end) }
        val (drop, cov) = Analyzer.hourlyDrain(dao.samplesSince(start), start)
        val idx = (0..23).filter { cov[it] >= 0.25 }
        if (idx.size < 6) { corr = emptyList(); return@launch }
        val y = idx.map { drop[it] / cov[it] }
        val pm = app.packageManager
        corr = per.mapNotNull { (pkg, arr) ->
            val x = idx.map { arr[it] }
            if (arr.sum() < 10.0) return@mapNotNull null
            val r = Analyzer.pearson(x, y) ?: return@mapNotNull null
            if (r < 0.5) return@mapNotNull null
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (ex: Exception) { pkg }
            Triple(label, r, arr.sum())
        }.sortedByDescending { it.second }.take(3)
    }

    fun hasUsageAccess(): Boolean {
        val ao = app.getSystemService(AppOpsManager::class.java)
        return ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun loadApps(days: Int) = viewModelScope.launch(Dispatchers.IO) {
        if (!hasUsageAccess()) { topApps = emptyList(); return@launch }
        val usm = app.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val pm = app.packageManager
        topApps = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - days * DAY, end)
            .groupBy { it.packageName }
            .mapValues { e -> e.value.sumOf { it.totalTimeInForeground } }
            .filter { it.value > 60_000L }
            .map { (pkg, t) ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                label to t
            }.sortedByDescending { it.second }.take(20)
    }
}
