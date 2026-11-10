package dev.couchtv.ontv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.PowerManager
import android.os.Process
import android.util.Log

/**
 * Fire OS and the home button. Amazon's TVs start Amazon's launcher from the Home key by explicit
 * component: the default-home setting is ignored, and the launcher is a protected package that can
 * be neither disabled nor suspended. This watches for Amazon's home coming on screen and puts this
 * app's home back on top. Amazon's home still flashes up for a moment; without root that is as
 * good as Fire OS allows.
 *
 * Some Fire OS builds also block the TV's own ADB (adbd drops any connection made from the TV
 * itself), so there are three ways to watch, tried in this order:
 *
 *  1. The accessibility watcher (WatchService), event-driven. Needs two one-off grants over ADB
 *     from a computer, kept across reboots:
 *       adb shell pm grant dev.couchtv.ontv android.permission.WRITE_SECURE_SETTINGS
 *       adb shell appops set dev.couchtv.ontv SYSTEM_ALERT_WINDOW allow
 *     Fire OS 6 runs Amazon's own accessibility manager and never binds it (issue #50), so the
 *     setting reads as on while nothing listens: the app then falls through to the next way.
 *  2. App-usage events, polled while the screen is on. Needs usage access, the third grant (give it
 *     on every Fire TV: it is only used where the watcher is not connected):
 *       adb shell appops set dev.couchtv.ontv GET_USAGE_STATS allow
 *  3. The activity log over the TV's own ADB, where that still works (no polling).
 *
 * Runs only on a Fire TV (Amazon's launcher is installed) with "Use as home screen" on, so it does
 * nothing on Google TV or any other Android TV. Poked at boot, on every ADB connect and from the
 * home-screen switch; looks after its own reconnects, so no computer has to stay on.
 */
object FireHome {
    private const val TAG = "couchtv.firehome"
    const val AMAZON_HOME = "com.amazon.tv.launcher"
    // The Home key shows up as ActivityTaskManager starting Amazon's launcher with CATEGORY_HOME.
    // -T1 skips everything already logged, so an old line never causes a jump at start.
    private const val WATCH = "shell:logcat -T1 -v brief ActivityTaskManager:I '*:S'"
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var lastJump = 0L
    @Volatile private var asideUntil = 0L
    private val jumps = ArrayDeque<Long>()

    // Google TV / Android TV homes outrank the default-home setting (seen on a TCL Android TV 11), so
    // the same watcher puts this home back over them. They stay installed and on: switching them
    // off can boot-loop a Google TV (README, "If your TV loops after a restart").
    const val GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx"
    const val ANDROID_TV_HOME = "com.google.android.tvlauncher"

    val isFireTv: Boolean get() = Tv.hasPackage(AMAZON_HOME)
    private val hasStockHome: Boolean get() = isFireTv || Tv.hasPackage(GOOGLE_TV_HOME) || Tv.hasPackage(ANDROID_TV_HOME)

    /** Usage access, the no-ADB way to see Amazon's home come forward. */
    val usageAllowed: Boolean
        get() = runCatching {
            App.instance.getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), App.instance.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

    private val wanted: Boolean
        get() = hasStockHome && App.homeEnabled(App.instance)

    /**
     * Amazon's launcher package also holds Fire OS's Settings screens (`.ui.MainSettingsActivity`
     * and others), so only its other screens count as Amazon's home.
     */
    fun isAmazonHome(pkg: String?, cls: String?) = pkg == AMAZON_HOME && cls?.contains("Settings") != true

    /** The TV's own home screen, not its other screens (Google's package also holds profile and setup screens). */
    fun isStockHome(pkg: String?, cls: String?) = when (pkg) {
        AMAZON_HOME -> isAmazonHome(pkg, cls)
        GOOGLE_TV_HOME -> cls == "$GOOGLE_TV_HOME.home.HomeActivity" || cls == "$GOOGLE_TV_HOME.home.VanillaModeHomeActivity"
        ANDROID_TV_HOME -> cls == "$ANDROID_TV_HOME.MainActivity"
        else -> false
    }

    /**
     * Just after a restart the TV's own home can come up before the watcher is bound, and that
     * window event is never seen. So when it binds in the first minutes after boot, look at what
     * last came forward (usage events, the grant this app gives itself over ADB): only if that is
     * the TV's own home does this home go on top. An HDMI input or live TV is never covered.
     */
    fun catchUpAfterBoot() {
        if (!wanted || android.os.SystemClock.elapsedRealtime() > 3 * 60_000L || !usageAllowed) return
        Thread {
            runCatching {
                Thread.sleep(1500)
                val usm = App.instance.getSystemService(UsageStatsManager::class.java)
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 5 * 60_000L, now)
                val e = UsageEvents.Event(); var pkg: String? = null; var cls: String? = null
                while (events.getNextEvent(e)) if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) { pkg = e.packageName; cls = e.className }
                Log.i(TAG, "bound after boot, last in front: $pkg/$cls")
                if (isStockHome(pkg, cls)) jump()
            }.onFailure { Log.w(TAG, "boot catch-up: ${it.message}") }
        }.start()
    }

    /** Whether the watcher should take the Home key itself (this home is on and not standing aside). */
    fun takesHomeKey() = wanted && System.currentTimeMillis() >= asideUntil

    /** The WatchService saw the TV's own home come on screen. */
    fun onStockHome() {
        if (wanted) jump()
    }

    /** Cheap to call often: starts the watcher once, only while it is wanted. */
    fun poke() {
        if (!wanted) return
        selfEnableWatch()
        if (WatchService.connected || !isFireTv) return   // event-driven; the polling fallbacks below are Fire OS only
        if (!(usageAllowed || Adb.everAllowed || Adb.paired)) return
        synchronized(lock) {
            if (running) return
            running = true
        }
        Thread({ watch() }, "firehome").start()
    }

    /**
     * With the secure-settings grant (`pm grant ... WRITE_SECURE_SETTINGS`, once over ADB from a
     * computer), switch the accessibility watcher on, and back on when an update or a settings
     * reset switches it off.
     */
    private fun selfEnableWatch() {
        val c = App.instance
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val cr = c.contentResolver
            val me = "${c.packageName}/${WatchService::class.java.name}"
            val cur = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services") ?: ""
            if (me !in cur.split(':')) {
                android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", if (cur.isEmpty()) me else "$cur:$me")
                Log.i(TAG, "switched the home watch service on")
            }
            android.provider.Settings.Secure.putInt(cr, "accessibility_enabled", 1)
        }.onFailure { Log.w(TAG, "could not switch the watch service on: ${it.message}") }
    }

    private fun watch() {
        try {
            while (wanted && !WatchService.connected) {
                if (usageAllowed) watchUsage() else watchAdb()
                Thread.sleep(3_000)
            }
        } catch (e: InterruptedException) {
        } finally {
            synchronized(lock) { running = false }
            Log.i(TAG, "watcher stopped")
            // "Use as home screen" may have come back on while the thread was winding down.
            if (wanted) poke()
        }
    }

    /** App-usage events, polled while the screen is on. No ADB involved. */
    private fun watchUsage() {
        Log.i(TAG, "watching for Amazon's home (usage events)")
        val usm = App.instance.getSystemService(UsageStatsManager::class.java)
        val power = App.instance.getSystemService(PowerManager::class.java)
        var since = System.currentTimeMillis()
        while (wanted && usageAllowed && !WatchService.connected) {
            Thread.sleep(if (power.isInteractive) 500 else 3_000)
            val now = System.currentTimeMillis()
            // A second of overlap, so an event on the boundary is never missed; the order check
            // below keeps a twice-seen event from causing a second jump.
            val events = runCatching { usm.queryEvents(since - 1_000, now) }.getOrNull() ?: continue
            since = now
            val e = UsageEvents.Event()
            var amazonInFront = false
            while (events.getNextEvent(e)) {
                if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                Log.i(TAG, "front: ${e.packageName}")
                when {
                    isAmazonHome(e.packageName, e.className) -> amazonInFront = true
                    e.packageName == BuildConfig.APPLICATION_ID || e.packageName == AMAZON_HOME -> amazonInFront = false
                }
            }
            if (amazonInFront) { Log.i(TAG, "Amazon's home came forward, jumping"); jump() }
        }
    }

    /** The activity log over the TV's own ADB: event-driven, for Fire OS builds that still allow it. */
    private fun watchAdb() {
        Log.i(TAG, "watching for Amazon's home (ADB log)")
        var guarded = false
        while (wanted && !usageAllowed && !WatchService.connected) {
            if (!Adb.connected) {
                guarded = false
                // Silent reconnect (the TV slept, adbd restarted). Never pops "Allow debugging?".
                if (!runCatching { Adb.ensure() }.getOrDefault(false)) { Thread.sleep(15_000); continue }
            }
            if (!guarded) { guard(); guarded = true }
            try {
                val stream = Adb.stream(WATCH)
                stream.openInputStream().bufferedReader().use { r ->
                    while (wanted) {
                        val line = r.readLine() ?: break
                        if ("android.intent.category.HOME" in line && AMAZON_HOME in line) jump()
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "watch: ${e.message}") }
            Thread.sleep(3_000)
        }
    }

    private fun jump() {
        if (!wanted) return
        val now = System.currentTimeMillis()
        if (now - lastJump < 1200) return   // one jump per press, not one per event
        if (now < asideUntil) return
        lastJump = now
        // If this home keeps falling back to the TV's own (it crashed, or will not open), stand aside
        // for ten minutes so the TV is never held in a loop.
        jumps.addLast(now)
        while (jumps.size > 6) jumps.removeFirst()
        if (jumps.size == 6 && now - jumps.first() < 15_000) {
            asideUntil = now + 10 * 60_000L; jumps.clear()
            Log.w(TAG, "jumped 6 times in 15 s: leaving the TV's own home alone for 10 minutes")
            return
        }
        App.openHome()
    }

    /** The home the system falls back to for everything that is not the Home key itself. Best-effort. */
    private fun guard() {
        runCatching { Adb.shell("cmd package set-home-activity ${BuildConfig.APPLICATION_ID}/.HomeAlias", 8_000) }
    }
}
