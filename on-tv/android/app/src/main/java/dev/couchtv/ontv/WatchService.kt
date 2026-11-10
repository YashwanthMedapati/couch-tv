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
    override fun onServiceConnected() { connected = true; FireHome.catchUpAfterBoot() }
    override fun onDestroy() { connected = false; super.onDestroy() }
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
        if (FireHome.isStockHome(e.packageName?.toString(), e.className?.toString())) FireHome.onStockHome()
        ProfilePicker.appCameForward(e.packageName?.toString())   // Netflix, Prime, YouTube: pick the right profile
    }

    companion object { @Volatile var connected = false }
}
