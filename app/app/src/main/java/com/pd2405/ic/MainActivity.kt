package com.pd2405.ic

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * ic98260-pd2405 runner — stages the exploit binary into /data/local/tmp via
 * Shizuku, runs the pre-flight ritual, executes the runner and streams its log.
 *
 * Everything here runs as `shell` (uid 2000, context u:r:shell:s0) through
 * Shizuku — the context the exploit was validated in (tracefs read access).
 *
 * UI notes carried over from the CLI protocol (docs/RUN-RECORD.md):
 *  - one attempt per boot; the pre-flight card enforces parked-process/hash checks;
 *  - when the root shell goes live, its input is this log view is NOT interactive —
 *    exit the run (Kill button / `exit`) to let the parent park, then reboot.
 */
class MainActivity : Activity(), Shizuku.OnRequestPermissionResultListener {

    companion object {
        /** Must match the sha256 of assets/ic98260 (ic98260-v5). Update both together. */
        const val EXPECTED_SHA = "2349665992649b3c56f71dded872e574c2ca0e288aa9ab6aca3f5c44f4857a9f"
        const val BIN = "/data/local/tmp/ic98260"
        const val REQ_PERM = 8260
    }

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private var proc: Process? = null

    private fun log(s: String) {
        runOnUiThread {
            logView.append(s + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** Run one shell command via Shizuku, return combined output. */
    private fun sh(cmd: String): String {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val out = p.inputStream.bufferedReader().readText()
        val err = try { p.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
        p.waitFor()
        return (out + err).trim()
    }

    private fun ready(): Boolean {
        if (!Shizuku.pingBinder()) {
            log("[!] Shizuku not reachable — open the Shizuku app and start the server first.")
            return false
        }
        if (Shizuku.checkSelfPermission() != 0) {
            Shizuku.requestPermission(REQ_PERM)
            return false
        }
        return true
    }

    /** Stream assets/ic98260 into /data/local/tmp and chmod 755 (shell-owned dir). */
    private fun stageBinary() {
        if (!ready()) return
        try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", "cat > $BIN && chmod 755 $BIN"), null, null)
            assets.open("ic98260").use { inp -> inp.copyTo(p.outputStream, 1 shl 16) }
            p.outputStream.close()
            p.waitFor()
            val got = sh("sha256sum $BIN")
            log(if (got.startsWith(EXPECTED_SHA)) "[+] staged, hash OK" else "[!] staged but hash MISMATCH: $got")
        } catch (e: Exception) {
            log("[!] stage failed: $e")
        }
    }

    /** The three pre-flight checks from docs/RUN-RECORD.md. */
    private fun preflight() {
        if (!ready()) return
        try {
            val parked = sh("ps -A | grep ic98260")
            log(if (parked.isEmpty()) "[+] no parked process" else "[-] PARKED PROCESS ALIVE — REBOOT FIRST:\n$parked")

            val uptime = sh("uptime")
            log("[*] $uptime")
            val min = Regex("up\\s+(\\d+)\\s+min").find(uptime)?.groupValues?.get(1)?.toIntOrNull() ?: 999
            if (min < 15) log("[!] uptime < 15 min — let post-boot churn settle")
            else log("[+] settled")

            val got = sh("sha256sum $BIN")
            log(if (got.startsWith(EXPECTED_SHA)) "[+] on-device binary = expected v5"
                else "[-] binary hash mismatch (push the right build first):\n$got")
        } catch (e: Exception) {
            log("[!] preflight failed: $e")
        }
    }

    /** Fire the exploit. One attempt per boot — the pre-flight card is not a formality. */
    private fun runExploit() {
        if (!ready()) return
        if (proc != null) { log("[!] a run is already active"); return }
        try {
            log("[*] firing — one attempt per boot; park/panic = reboot, never re-run")
            val p = Shizuku.newProcess(arrayOf(BIN, "shell"), null, null)
            proc = p
            Thread {
                p.inputStream.bufferedReader().forEachLine { log(it) }
                log("[*] run process ended — if it parked, the phone stays frozen-safe; reboot to clear")
                runOnUiThread { proc = null }
            }.start()
        } catch (e: Exception) {
            log("[!] run failed: $e")
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQ_PERM)
            log(if (grantResult == 0) "[+] Shizuku granted" else "[-] Shizuku denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(pad, pad, pad, pad)
        }

        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

        root.addView(button("Grant Shizuku") {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(REQ_PERM)
            else log("[!] start the Shizuku server first")
        })
        root.addView(button("Stage binary") { stageBinary() })
        root.addView(button("Pre-flight") { preflight() })
        root.addView(button("Run exploit") { runExploit() })
        root.addView(button("Kill run / exit root shell") {
            proc?.destroy(); proc = null; log("[*] killed — parent parks; reboot to clear")
        })

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(190, 255, 190))
            setPadding(0, pad, 0, pad)
            text = "ic98260-pd2405 — CVE-2026-98260 temporary root (ephemeral; reboot = clean)\n" +
                   "Expected binary: sha256 $EXPECTED_SHA\n\n"
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        Shizuku.addRequestPermissionResultListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(this)
        proc?.destroy()
    }
}
