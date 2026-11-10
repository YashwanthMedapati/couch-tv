package dev.couchtv.ontv

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Sees Amazon's home come on screen, event for event, on Fire OS builds that give third-party
 * apps neither their own ADB nor usage events (both seen blocked on Fire OS 8.1.8.5 / RS8185).
 * FireHome switches this service on with the secure-settings grant, and Android keeps it bound
 * across reboots, so the Home key keeps landing on this app's home with no computer involved.
 */
class WatchService : AccessibilityService() {
    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            FireHome.screenOnAt = System.currentTimeMillis()
            android.util.Log.i("couchtv.firehome", "screen on, last in front: $lastFront")
            // Coming back to a home (this one or the TV's own): put this one up straight away, before
            // the TV's start-up screens. Anything else (Lucid, an HDMI input) is left as it was.
            if (lastFront == packageName || lastFront == FireHome.GOOGLE_TV_HOME) FireHome.onStockHome()
        }
    }

    override fun onServiceConnected() {
        connected = true
        registerReceiver(screenOn, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON))
        FireHome.catchUpAfterBoot()
    }
    override fun onDestroy() { connected = false; runCatching { unregisterReceiver(screenOn) }; super.onDestroy() }
    override fun onInterrupt() {}

    /**
     * The Home key itself: open this home straight away, so the TV's own home never comes up first
     * (no flash). Every other key passes through untouched. When this home is off, or standing
     * aside after failing to open, Home goes to the TV as usual.
     */
    override fun onKeyEvent(e: android.view.KeyEvent): Boolean {
        if (e.keyCode != android.view.KeyEvent.KEYCODE_HOME) return false
        android.util.Log.i("couchtv.firehome", "Home key ${if (e.action == android.view.KeyEvent.ACTION_DOWN) "down" else "up"}")
        if (!FireHome.takesHomeKey()) return false
        if (e.action == android.view.KeyEvent.ACTION_UP) FireHome.onStockHome()
        return true
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString(); val cls = e.className?.toString()
        // What was really on screen, not the start-up and profile screens or the system's own overlays
        if (pkg != null && pkg != "com.android.systemui" && pkg != "android" && !FireHome.isWakeScreen(pkg, cls) &&
            cls?.endsWith(".bootmode.DispatchActivity") != true) lastFront = pkg   // Google puts that up as the screen goes off
        if (pkg == FireHome.GOOGLE_TV_HOME) android.util.Log.i("couchtv.firehome", "front: $cls, ${System.currentTimeMillis() - FireHome.screenOnAt} ms after screen on")
        if (FireHome.isStockHome(pkg, cls) || FireHome.isWakeScreen(pkg, cls)) FireHome.onStockHome()
        ProfilePicker.appCameForward(e.packageName?.toString())   // Netflix, Prime, YouTube: pick the right profile
    }

    companion object { @Volatile var connected = false; @Volatile var lastFront: String? = null }
}
