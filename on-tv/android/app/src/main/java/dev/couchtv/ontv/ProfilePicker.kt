package dev.couchtv.ontv

import android.util.Log

/**
 * Picks the right profile on Netflix, Prime Video and YouTube "Who's watching?" screens: the person
 * chosen on the home (people[].app_profiles if their name differs in an app, else their own name).
 * Started by the home watch when one of those apps comes forward. Reads the screen a few times
 * through the TV's own ADB (uiautomator, as the computer bridge does) and taps the matching name;
 * gives up quietly if there is no such screen or no match.
 */
object ProfilePicker {
    private const val TAG = "couchtv.profiles"
    private val APPS = setOf("com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv")
    private val WHO_SCREEN = Regex("who.?s watching|who is watching|choose (?:a |your )?profile|select (?:a )?profile|who.?s using", RegexOption.IGNORE_CASE)
    // Never a sign-in list: "Choose an account to continue to Netflix" is Google sign-in, not a profile screen.
    private val SIGN_IN = Regex("@|sign in|continue to", RegexOption.IGNORE_CASE)
    private val NODE = Regex("<node [^>]*>")
    private fun attr(node: String, name: String) = Regex("$name=\"([^\"]*)\"").find(node)?.groupValues?.get(1).orEmpty()
    private val lastRun = HashMap<String, Long>()
    @Volatile private var busy = false

    /** One of the three apps came forward. Cheap to call on every window change. */
    fun appCameForward(pkg: String?) {
        if (pkg == null || pkg !in APPS || busy || !Adb.connected) return
        val now = System.currentTimeMillis()
        synchronized(lastRun) {
            if (now - (lastRun[pkg] ?: 0) < 60_000) return
            lastRun[pkg] = now
        }
        val names = namesFor(Now.who) ?: return
        busy = true
        Thread({ try { pick(pkg, names) } catch (e: Exception) { Log.w(TAG, "${e.message}") } finally { busy = false } }, "profiles").start()
    }

    /** The current person's names to look for, best first. Null when nobody is picked. */
    private fun namesFor(who: String?): List<String>? {
        val people = Store.people()
        for (i in 0 until people.length()) {
            val p = people.optJSONObject(i) ?: continue
            if (p.optString("id") != who) continue
            val own = p.optJSONArray("app_profiles")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return (own + p.optString("name")).filter { it.isNotBlank() }
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun pick(pkg: String, names: List<String>) {
        Thread.sleep(2500)   // the app's first screen
        repeat(4) {
            // Shell-private temp folder (not /sdcard, which other apps can read), removed straight after.
            val f = "/data/local/tmp/couch-ui.xml"
            val xml = Adb.shell("uiautomator dump $f >/dev/null 2>&1; cat $f; rm -f $f", 12_000)
            val nodes = NODE.findAll(xml).map { it.value }.toList()
            if (nodes.isNotEmpty() && nodes.none { attr(it, "package") == pkg }) return   // the person left the app
            val labelled = nodes.map { (attr(it, "text").ifEmpty { attr(it, "content-desc") }) to it }.filter { it.first.isNotBlank() }
            if (labelled.any { SIGN_IN.containsMatchIn(it.first) }) return
            if (labelled.any { WHO_SCREEN.containsMatchIn(it.first) }) {
                val hit = names.firstNotNullOfOrNull { n -> labelled.firstOrNull { norm(it.first) == norm(n) || norm(it.first).startsWith(norm(n)) } }
                if (hit == null) { Log.i(TAG, "profile screen in $pkg, none of $names on it"); return }
                val b = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(attr(hit.second, "bounds"))?.groupValues ?: return
                val x = (b[1].toInt() + b[3].toInt()) / 2; val y = (b[2].toInt() + b[4].toInt()) / 2
                Adb.shell("input tap $x $y", 8_000)
                Log.i(TAG, "picked ${hit.first} in $pkg")
                return
            }
            Thread.sleep(2000)
        }
    }
}
