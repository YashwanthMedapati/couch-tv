package dev.couchtv.ontv

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The Couch TV screensaver: the photo slideshow with the home's clock (tv-home/web/saver.html, served
 * by this app). Chosen in the TV's Settings, Screen saver. Any key wakes the TV, as with every
 * screensaver.
 */
class SaverDream : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        App.startService(this)
        // the page asks this app for the weather, signed in the way the home is
        android.webkit.CookieManager.getInstance().setCookie("http://127.0.0.1:${Store.port}", "couchtv_token=${Auth.internalToken}; path=/")
        val w = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                // The server may still be starting just after a restart: try again shortly.
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) main.postDelayed({ load() }, 1500)
                }
            }
        }
        web = w
        setContentView(w)
        load()
    }

    private fun load() { web?.loadUrl("http://127.0.0.1:${Store.port}/tv/saver.html") }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        web?.destroy(); web = null
        super.onDetachedFromWindow()
    }
}
