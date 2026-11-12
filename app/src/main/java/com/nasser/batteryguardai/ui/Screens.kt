package com.nasser.batteryguardai.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nasser.batteryguardai.data.*
import com.nasser.batteryguardai.engine.*
import java.text.SimpleDateFormat
import java.util.*

private val tabs = listOf("الرئيسية", "البطارية", "التحليل", "الأتمتة", "الإعدادات")
private val tabIcons = listOf(Icons.Filled.Home, Icons.Filled.Favorite, Icons.Filled.Search, Icons.Filled.Refresh, Icons.Filled.Settings)
private val timeFmt = SimpleDateFormat("MM/dd HH:mm", Locale.US)
private fun t(ms: Long) = timeFmt.format(Date(ms))

@Composable fun AppRoot(vm: VM) {
    val dark = when (vm.themeMode) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BgTheme(dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var sub by rememberSaveable { mutableStateOf<String?>(null) }
                BackHandler(sub != null) { sub = null }
                Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { i, l ->
                            NavigationBarItem(selected = tab == i && sub == null, onClick = { tab = i; sub = null },
                                icon = { Icon(tabIcons[i], null) }, label = { Text(l, fontSize = 11.sp) })
                        }
                    }
                }) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (sub) {
                            "apps" -> AppsScreen(vm) { sub = null }
                            "samsung" -> SamsungScreen { sub = null }
                            "modes" -> ModesScreen(vm) { sub = null }
                            "history" -> HistoryScreen(vm) { sub = null }
                            "about" -> AboutScreen { sub = null }
                            else -> when (tab) {
                                0 -> HomeScreen(vm) { sub = it }
                                1 -> BatteryScreen(vm)
                                2 -> AnalysisScreen(vm) { sub = it }
                                3 -> AutomationScreen(vm)
                                else -> SettingsScreen(vm) { sub = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun SubPage(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) = Page(title) {
    TextButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("رجوع") }
    content()
}

@Composable fun HomeScreen(vm: VM, go: (String) -> Unit) {
    val s by vm.live.collectAsStateWithLifecycle()
    val all by vm.samples.collectAsStateWithLifecycle()
    val recs by vm.recs.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val day = remember(all) { all.filter { it.time > now - DAY } }
    val dr = remember(all) { Analyzer.dischargeRate(day) }
    val cr = remember(all) { Analyzer.chargeRate(day) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val attention = s.tempC >= 40f || recs.any { now - it.time < 6 * HOUR }
    Page("لوحة التحكم") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Ring(s.level, s.charging) }
        Panel("ملخص حالة البطارية") {
            Text(if (attention) "تستحق الانتباه" else "الحالة طبيعية", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (attention) Warn else Emerald)
            Text("الحرارة: " + if (s.tempC >= 45f) "مرتفعة جدًا" else if (s.tempC >= 40f) "مرتفعة" else "طبيعية")
        }
        StatRow("الحرارة" to "${f1(s.tempC.toDouble())}°م", "الجهد" to "${f1(s.voltageMv / 1000.0)} فولت")
        StatRow("التيار" to if (s.currentUa == 0) "غير متاح" else "${kotlin.math.abs(s.currentUa) / 1000} مللي أمبير",
            "معدل الاستهلاك" to pct(dr?.perHour))
        if (s.charging) StatRow("حتى اكتمال الشحن" to hm(Analyzer.hoursToFull(s.level, cr)), "معدل الشحن" to pct(cr?.perHour))
        else StatRow("الوقت المتبقي (تقدير)" to hm(Analyzer.remainingHours(s.level, dr)),
            "آخر شحن" to (sessions.firstOrNull()?.let { t(it.startTime) } ?: "لا يوجد"))
        recs.firstOrNull()?.let { r -> Panel("التوصية الذكية الأهم") { Text(r.title, fontWeight = FontWeight.Bold); Text(r.action) } }
        Panel("الأقسام") {
            listOf("apps" to "مرصد استهلاك التطبيقات", "modes" to "أوضاع توفير الطاقة", "samsung" to "تكامل Samsung One UI",
                "history" to "سجل الطاقة والتقارير", "about" to "حول التطبيق").forEach { (k, l) ->
                TextButton(onClick = { go(k) }, modifier = Modifier.fillMaxWidth()) { Text(l, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable fun BatteryScreen(vm: VM) {
    val s by vm.live.collectAsStateWithLifecycle()
    val all by vm.samples.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val day = remember(all) { all.filter { it.time > now - DAY } }
    val cap = remember(all) { Analyzer.capacityEstimateMah(all) }
    Page("مراقبة البطارية") {
        Panel("مستوى البطارية خلال 24 ساعة") {
            if (day.size < 2) Text("لا توجد بيانات كافية بعد. يجمع التطبيق قياسًا كل 15 دقيقة تقريبًا.")
            else LineChart(day.map { it.level.toFloat() }, 0f, 100f, Emerald, Modifier.fillMaxWidth().height(140.dp))
            Text("عدد القياسات المسجلة: ${all.size}", fontSize = 12.sp)
        }
        Panel("القياسات الحالية") {
            Text("المستوى: ${s.level}%  |  ${if (s.charging) "شحن" else "تفريغ"}")
            Text("الحرارة: ${f1(s.tempC.toDouble())}°م  |  الجهد: ${s.voltageMv} مللي فولت")
            Text("التيار: " + if (s.currentUa == 0) "غير متاح على هذا الجهاز" else "${s.currentUa / 1000} مللي أمبير (الإشارة تختلف بحسب الجهاز)")
        }
        Panel("مختبر الشحن") {
            val avg = sessions.filter { it.endTime != null }.map { it.endTime!! - it.startTime }
            Text("عدد الجلسات: ${sessions.size}" + if (avg.isNotEmpty()) "  |  متوسط المدة: ${dur(avg.average().toLong())}" else "")
            if (sessions.isEmpty()) Text("ستظهر الجلسات بعد أول عملية شحن.")
            sessions.take(8).forEach {
                Text("${t(it.startTime)}: ${it.startLevel}% ← ${it.endLevel ?: "؟"}%  |  أعلى حرارة ${f1(it.maxTempC.toDouble())}°م" +
                    (it.endTime?.let { e -> "  |  ${dur(e - it.startTime)}" } ?: "  |  جارية"), fontSize = 13.sp)
            }
            Text("ضبط تنبيهات نسب الشحن (70/80/85/90/100) من قسم الأتمتة.", fontSize = 12.sp)
        }
        Panel("مراقبة الحرارة") {
            val hot = day.count { it.tempC >= 40f }
            Text("أعلى حرارة خلال 24 ساعة: " + (day.maxOfOrNull { it.tempC }?.let { "${f1(it.toDouble())}°م" } ?: "غير متاح"))
            Text("عدد القياسات فوق 40°م: $hot")
            if (day.size >= 2) LineChart(day.map { it.tempC }, 20f, 50f, Warn, Modifier.fillMaxWidth().height(100.dp))
            Text("يُقرأ التطبيق حرارة البطارية فقط؛ لا يتيح Android حساسات المعالج الداخلية لتطبيق عادي.", fontSize = 12.sp)
        }
        Panel("مختبر صحة البطارية") {
            Text("السعة التصميمية: غير متاحة عبر واجهات Android العامة.")
            Text("تقدير السعة الحالية: " + (cap?.let { "≈ $it مللي أمبير ساعة (تقدير)" } ?: "بيانات غير كافية"))
            Text("طريقة الحساب: الوسيط لقيمة (عداد الشحن ÷ نسبة المستوى) خلال القياسات بين 30% و95%. هذا تقدير تقريبي وليس نسبة صحة دقيقة، ويتأثر بدقة عداد الجهاز.", fontSize = 12.sp)
            Text("تتبّع الاتجاه يحتاج أسابيع من البيانات؛ لا تُعرض نسبة صحة دون مصدر موثوق.", fontSize = 12.sp)
        }
    }
}

@Composable fun AnalysisScreen(vm: VM, go: (String) -> Unit) {
    val all by vm.samples.collectAsStateWithLifecycle()
    val recs by vm.recs.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val today = remember(all) { all.filter { it.time > now - DAY } }
    val base = remember(all) { all.filter { it.time <= now - DAY && it.time > now - 8 * DAY } }
    Page("المحلل الذكي للطاقة") {
        Panel("ملخص الاستهلاك") {
            val rT = Analyzer.dischargeRate(today); val rB = Analyzer.dischargeRate(base)
            Text("معدل اليوم: ${pct(rT?.perHour)}"); Text("متوسطك المعتاد: ${pct(rB?.perHour)}")
            Text("أثناء الشاشة مفعلة: ${pct(Analyzer.dischargeRate(today, screenOnOnly = true)?.perHour)}")
            Text("أثناء الشاشة مطفأة: ${pct(Analyzer.dischargeRate(today, screenOffOnly = true)?.perHour)}")
            rT?.let { Text("درجة الثقة: ${it.confidence}%", fontSize = 12.sp) }
            if (rT == null) Text("نحتاج إلى ساعات إضافية من البيانات قبل التحليل.", fontSize = 12.sp)
            Button(onClick = { vm.analyzeNow() }) { Text("حلّل الآن") }
        }
        TextButton(onClick = { go("apps") }) { Text("عرض مرصد استهلاك التطبيقات") }
        if (recs.isEmpty()) Panel { Text("لا توجد توصيات حاليًا. لا يُنشئ التطبيق توصية إلا عند وجود دليل كافٍ.") }
        recs.take(10).forEach { r ->
            Panel(r.title) {
                Text("المشكلة: ${r.problem}"); Text("الدليل: ${r.evidence}"); Text("السبب المحتمل: ${r.cause}")
                Text("الإجراء المقترح: ${r.action}"); Text("درجة الثقة: ${r.confidence}%  •  ${t(r.time)}", fontSize = 12.sp)
            }
        }
    }
}

@Composable fun AutomationScreen(vm: VM) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    Page("الأتمتة الذكية") {
        Button(onClick = { show = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("قاعدة جديدة") }
        rules.forEach { r ->
            Panel(r.name) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((Automation.triggers[r.trigger] ?: r.trigger) +
                        if (r.trigger in listOf("LEVEL_BELOW", "CHARGE_REACHED", "TEMP_ABOVE")) " : ${r.threshold}" else "", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(r.enabled, { vm.toggleRule(r) })
                    IconButton({ vm.deleteRule(r) }) { Icon(Icons.Filled.Delete, "حذف") }
                }
                Text("الإجراء: إشعار + تسجيل في السجل. لا تتكرر قبل ${r.cooldownMin} دقيقة." +
                    if (r.startHour == 0 && r.endHour == 24) "" else " تعمل من الساعة ${r.startHour} إلى ${r.endHour}.", fontSize = 11.sp)
            }
        }
        Panel("سجل التنفيذ") {
            if (logs.isEmpty()) Text("لم تُنفَّذ أي قاعدة بعد.")
            logs.take(20).forEach { Text("${t(it.time)} — ${it.ruleName}: ${it.message}", fontSize = 12.sp) }
        }
        Text("تُقيَّم القواعد عند كل قياس (كل 15 دقيقة تقريبًا، أو 30/60 في أوضاع التوفير)، فقد يتأخر التنبيه عن اللحظة الفعلية.", fontSize = 11.sp)
    }
    if (show) RuleDialog({ show = false }) { n, tr, th, f, t2 -> vm.addRule(n, tr, th, f, t2); show = false }
}

@Composable fun RuleDialog(dismiss: () -> Unit, save: (String, String, Int, Int, Int) -> Unit) {
    var from by remember { mutableStateOf("0") }
    var to by remember { mutableStateOf("24") }
    var name by remember { mutableStateOf("") }
    var trig by remember { mutableStateOf("CHARGE_REACHED") }
    var th by remember { mutableStateOf("80") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("قاعدة جديدة") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("اسم القاعدة") }, singleLine = true)
            Automation.triggers.forEach { (k, l) ->
                Row(Modifier.clickable { trig = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(trig == k, { trig = k }); Text(l, fontSize = 13.sp)
                }
            }
            if (trig in listOf("LEVEL_BELOW", "CHARGE_REACHED", "TEMP_ABOVE"))
                OutlinedTextField(th, { th = it.filter(Char::isDigit).take(3) }, label = { Text("القيمة") }, singleLine = true)
            Text("وقت التنفيذ (الساعات 0–24؛ 0 إلى 24 = طوال اليوم)", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(from, { from = it.filter(Char::isDigit).take(2) }, label = { Text("من") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(to, { to = it.filter(Char::isDigit).take(2) }, label = { Text("إلى") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
    }, confirmButton = {
        TextButton(onClick = { save(name.ifBlank { Automation.triggers[trig] ?: "قاعدة" }, trig, th.toIntOrNull() ?: 0, from.toIntOrNull() ?: 0, to.toIntOrNull() ?: 24) }) { Text("حفظ") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}

@Composable fun SettingsScreen(vm: VM, go: (String) -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val n by vm.samples.collectAsStateWithLifecycle()
    Page("الإعدادات") {
        Panel("المظهر") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("النظام", "داكن", "فاتح").forEachIndexed { i, l ->
                    FilterChip(vm.themeMode == i, { vm.setTheme(i) }, { Text(l) })
                }
            }
        }
        Panel("المراقبة") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("المراقبة منخفضة الاستهلاك (قياس كل 60 دقيقة)", Modifier.weight(1f)); Switch(vm.lowPower, { vm.setLow(it) })
            }
            Text("الفترة الحالية: ${Scheduler.intervalMin(ctx)} دقيقة", fontSize = 12.sp)
        }
        Panel("التنبيهات") {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("تنبيهات البطارية والأتمتة", Modifier.weight(1f)); Switch(vm.notifAlerts, { vm.setAlerts(it) }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("تنبيهات التحليل والتوصيات", Modifier.weight(1f)); Switch(vm.notifAnalysis, { vm.setAnalysis(it) }) }
        }
        Panel("البيانات والخصوصية") {
            Text("جميع البيانات محلية على جهازك (${n.size} قياسًا). لا يوجد اتصال بالإنترنت ولا تتبع ولا إعلانات.", fontSize = 13.sp)
            Button(onClick = { ctx.startActivity(vm.exportCsv()) }) { Text("تصدير CSV") }
            Button(onClick = { ctx.startActivity(vm.exportPdf()) }) { Text("تصدير تقرير PDF") }
            OutlinedButton(onClick = { confirm = true }) { Text("حذف جميع البيانات") }
        }
        Panel("الصلاحيات") {
            Text("إشعارات: لتنبيهات البطارية. وصول بيانات الاستخدام: اختياري، لعرض أكثر التطبيقات استخدامًا فقط.", fontSize = 13.sp)
            OutlinedButton(onClick = { openSafe(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("فتح إعداد الوصول لبيانات الاستخدام") }
            OutlinedButton(onClick = { openSafe(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }) { Text("إعدادات الإشعارات") }
        }
        Panel("تشخيص") { Text("الإصدار 1.0.0  |  الحزمة com.nasser.batteryguardai  |  آخر قياس: ${n.lastOrNull()?.let { t(it.time) } ?: "لا يوجد"}", fontSize = 12.sp) }
        TextButton(onClick = { go("about") }) { Text("حول التطبيق") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("حذف جميع البيانات؟") },
        text = { Text("سيُحذف سجل القياسات والجلسات والتوصيات. لا يمكن التراجع.") },
        confirmButton = { TextButton({ vm.deleteAll(); confirm = false }) { Text("حذف") } },
        dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } })
}

fun openSafe(ctx: Context, vararg intents: Intent): Boolean {
    for (i in intents) try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) { }
    return false
}

@Composable fun AppsScreen(vm: VM, back: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(vm.hasUsageAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = vm.hasUsageAccess(); if (granted) { vm.loadApps(1); vm.loadCorrelation() } }
    SubPage("مرصد استهلاك التطبيقات", back) {
        if (!granted) Panel("الصلاحية مطلوبة") {
            Text("لعرض أكثر التطبيقات استخدامًا يحتاج التطبيق إلى «الوصول إلى بيانات الاستخدام». تبقى البيانات على جهازك.")
            Button(onClick = { openSafe(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("منح الصلاحية") }
        } else Panel("أكثر التطبيقات استخدامًا اليوم") {
            if (vm.topApps.isEmpty()) Text("لا توجد بيانات كافية بعد.")
            vm.topApps.forEach { (n, ms) -> Row { Text(n, Modifier.weight(1f)); Text(dur(ms)) } }
            Text("هذه مدة الاستخدام الأمامي. لا يوفر Android استهلاك الطاقة الدقيق لكل تطبيق، لذلك لا ننسب الاستنزاف لتطبيق بعينه.", fontSize = 12.sp)
        }
        if (granted) Panel("تطبيقات يتزامن استخدامها مع ارتفاع التفريغ") {
            if (vm.corr.isEmpty()) Text("لا يوجد تطبيق يُظهر ارتباطًا واضحًا (أو البيانات غير كافية بعد).", fontSize = 13.sp)
            vm.corr.forEach { (n, r, m) -> Text("$n — ارتباط ${f1(r)} (استخدام ${m.toInt()} د خلال 24 ساعة)", fontSize = 13.sp) }
            Text("هذا ارتباط زمني بين ساعات الاستخدام ومعدل التفريغ، وليس إثباتًا أن التطبيق سبب الاستنزاف.", fontSize = 11.sp)
        }
        Panel("إدارة التطبيقات") {
            OutlinedButton(onClick = { openSafe(ctx, Intent(Settings.ACTION_APPLICATION_SETTINGS)) }) { Text("فتح إعدادات التطبيقات") }
            OutlinedButton(onClick = { openSafe(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("تحسين البطارية للتطبيقات") }
        }
    }
}

@Composable fun SamsungScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val lool = Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
    SubPage("تكامل Samsung One UI", back) {
        Panel("اختصارات الإعدادات") {
            listOf(
                "إعدادات البطارية (Samsung)" to arrayOf(lool, Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_SETTINGS)),
                "وضع توفير الطاقة" to arrayOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
                "إعدادات الشاشة ومعدل التحديث" to arrayOf(Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
                "الشبكات والاتصالات" to arrayOf(Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
                "إدارة التطبيقات" to arrayOf(Intent(Settings.ACTION_APPLICATION_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            ).forEach { (l, ints) -> OutlinedButton(onClick = { openSafe(ctx, *ints) }, modifier = Modifier.fillMaxWidth()) { Text(l) } }
        }
        Panel("حماية البطارية") {
            Text("افتح: الإعدادات ← البطارية ← حماية البطارية (في بعض إصدارات One UI: الإعدادات ← البطارية ← المزيد من إعدادات البطارية).")
            Text("الأوضاع الشائعة: أساسي (حتى 100%)، تكيّفي، حد أقصى (80% أو 85% أو 90% بحسب الإصدار). توفرها يختلف بين إصدارات One UI.")
            Text("لا يستطيع تطبيق عادي إيقاف الشحن برمجيًا؛ لذلك نكتفي بتنبيهك عند بلوغ النسبة المحددة وإرشادك لإعداد النظام.")
            OutlinedButton(onClick = { openSafe(ctx, lool, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Settings.ACTION_SETTINGS)) }) { Text("فتح إعدادات البطارية") }
        }
        Panel("إرشادات لتقليل الاستهلاك") {
            Text("• خفّض السطوع أو فعّل السطوع التكيّفي.\n• جرّب معدل التحديث التكيّفي (60–120Hz) أو القياسي 60Hz عند الحاجة.\n• استخدم Wi-Fi بدل بيانات الهاتف عند توفره.\n• قيّد نشاط الخلفية للتطبيقات التي نادرًا ما تستخدمها.\n• فعّل الوضع الداكن؛ شاشة AMOLED توفّر طاقة معه.")
        }
        Text("لا يعتمد التطبيق على Knox أو صلاحيات خاصة بالنظام.", fontSize = 12.sp)
    }
}

private val modeInfo = listOf(
    "الوضع الاقتصادي" to "قياس كل 30 دقيقة، وتوصيات لتقليل الهدر (السطوع، الاتصالات غير الضرورية)، واقتراح تفعيل توفير الطاقة في النظام.",
    "وضع النوم" to "يراقب الاستنزاف أثناء الليل ويعرضه في التحليل (القياس أثناء إطفاء الشاشة). راجع استنزاف عدم الاستخدام صباحًا في شاشة التحليل.",
    "وضع الطوارئ" to "للبطارية المنخفضة: استخدم قاعدة «انخفاض البطارية» لتنبيهك، وفعّل توفير الطاقة من النظام لتقليل الاستهلاك.",
    "الأداء المتوازن (افتراضي)" to "قياس كل 15 دقيقة مع الحفاظ على تجربة الاستخدام دون توصيات مزعجة.",
    "الوضع المخصص" to "تختار بنفسك فترة القياس، وتنشئ قواعد الأتمتة والتنبيهات التي تناسبك."
)

@Composable fun ModesScreen(vm: VM, back: () -> Unit) {
    val ctx = LocalContext.current
    SubPage("أوضاع توفير الطاقة", back) {
        modeInfo.forEachIndexed { i, (n, d) ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.setMode(i) }) {
                    RadioButton(vm.mode == i, { vm.setMode(i) }); Text(n, fontWeight = FontWeight.Bold)
                }
                Text(d, fontSize = 13.sp)
                if (i == 4 && vm.mode == 4) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { v -> FilterChip(vm.customInterval == v, { vm.setCustomInterval(v) }, { Text("$v د") }) }
                }
            }
        }
        Text("يغيّر الوضع فترة القياس فقط. لا يغيّر التطبيق إعدادات النظام دون تدخلك.", fontSize = 12.sp)
        OutlinedButton(onClick = { openSafe(ctx, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }) { Text("فتح توفير الطاقة في النظام") }
    }
}

@Composable fun HistoryScreen(vm: VM, back: () -> Unit) {
    val ctx = LocalContext.current
    val all by vm.samples.collectAsStateWithLifecycle()
    val notifs by vm.notifs.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val dayFmt = remember { SimpleDateFormat("MM/dd", Locale.US) }
    SubPage("سجل الطاقة والتقارير", back) {
        Panel("المقارنات") {
            fun r(from: Long, to: Long) = Analyzer.dischargeRate(all.filter { it.time in from..to })?.perHour
            Text("اليوم: ${pct(r(now - DAY, now))}  |  الأمس: ${pct(r(now - 2 * DAY, now - DAY))}")
            Text("هذا الأسبوع: ${pct(r(now - 7 * DAY, now))}  |  السابق: ${pct(r(now - 14 * DAY, now - 7 * DAY))}")
            Text("هذا الشهر: ${pct(r(now - 30 * DAY, now))}")
        }
        Panel("متوسط الاستهلاك اليومي (آخر 7 أيام)") {
            for (d in 0..6) {
                val to = now - d * DAY
                Text("${dayFmt.format(Date(to))}: ${pct(Analyzer.dischargeRate(all.filter { it.time in (to - DAY)..to })?.perHour)}", fontSize = 13.sp)
            }
        }
        Panel("سجل التنبيهات") {
            if (notifs.isEmpty()) Text("لا توجد تنبيهات.")
            notifs.take(30).forEach { Text("${t(it.time)} — ${it.text}", fontSize = 12.sp) }
        }
        Button(onClick = { ctx.startActivity(vm.exportCsv()) }) { Text("تصدير CSV") }
        Button(onClick = { ctx.startActivity(vm.exportPdf()) }) { Text("تصدير تقرير PDF") }
    }
}

@Composable fun AboutScreen(back: () -> Unit) = SubPage("حول التطبيق", back) {
    Panel("حارس البطارية الذكي — الإصدار 1.0.0") {
        Text("مراقبة البطارية وتحليل الاستهلاك محليًا دون إنترنت أو اشتراكات.")
        Text("الخصوصية: لا رفع للبيانات، لا إعلانات، لا تتبع.")
    }
    Panel("القيود المعروفة") {
        Text("• السعة التصميمية ونسبة الصحة الدقيقة غير متاحة عبر Android العام؛ نعرض تقديرًا موضحًا فقط.\n• استهلاك الطاقة لكل تطبيق غير متاح؛ نعرض مدة الاستخدام فقط.\n• القياس الخلفي دوري (15 دقيقة على الأقل بحسب WorkManager).\n• لا يمكن إيقاف الشحن برمجيًا.\n• التحليل إحصائي وقواعد؛ لا يوجد نموذج تعلم آلي في هذا الإصدار.\n• ربط التطبيقات بالتفريغ ارتباط زمني فقط.")
    }
}
