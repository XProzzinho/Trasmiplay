package com.geanalves.transmiplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Atualização automática: olha a última versão publicada no GitHub,
 * baixa o APK novo e instala por cima (sem perder nada).
 */
object Updater {
    // ---- muda só isso entre os apps
    private const val REPO = "XProzzinho/Trasmiplay"
    private const val ASSET = "Transmiplay.apk"
    // ----

    data class Release(val version: Int, val name: String, val apkUrl: String, val notes: String)

    enum class Stage { IDLE, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }

    @Volatile var stage = Stage.IDLE; private set
    @Volatile var release: Release? = null; private set
    @Volatile var percent = 0; private set
    @Volatile var message = ""; private set
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var lastCheck = 0L
    private fun changed() = main.post { listener?.invoke() }
    fun clearMessage() { message = "" }

    fun currentVersion(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    /** procura versão nova (no máximo a cada 30 min, a não ser que force) */
    fun check(ctx: Context, force: Boolean = false, autoDownload: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 30 * 60_000) return
        if (stage == Stage.DOWNLOADING || stage == Stage.INSTALLING) return
        lastCheck = now
        val app = ctx.applicationContext
        thread(isDaemon = true) {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Transmiplay-updater")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.optString("tag_name")
                val ver = tag.filter { it.isDigit() }.toIntOrNull() ?: return@thread
                val assets = j.optJSONArray("assets") ?: return@thread
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == ASSET) url = a.optString("browser_download_url")
                }
                if (url == null) return@thread
                if (ver > currentVersion(app)) {
                    release = Release(ver, j.optString("name", tag), url, j.optString("body"))
                    if (stage != Stage.READY) stage = Stage.AVAILABLE
                    changed()
                    if (autoDownload) download(app)
                } else if (force) {
                    message = "Você já está na versão mais nova"
                    changed()
                }
            } catch (e: Exception) {
                if (force) { message = "Não consegui verificar agora"; changed() }
            }
        }
    }

    private fun apkFile(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** baixa o APK novo */
    fun download(ctx: Context, thenInstall: Boolean = false) {
        val r = release ?: return
        if (stage == Stage.DOWNLOADING) return
        val app = ctx.applicationContext
        stage = Stage.DOWNLOADING; percent = 0; changed()
        thread(isDaemon = true) {
            try {
                var c = URL(r.apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Transmiplay-updater")
                // o GitHub redireciona pra outro endereço
                var hops = 0
                while (c.responseCode in 300..399 && hops++ < 5) {
                    val loc = c.getHeaderField("Location") ?: break
                    c = URL(URL(r.apkUrl), loc).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                }
                val total = c.contentLengthLong
                val tmp = File(app.cacheDir, "update.part")
                c.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != lastPct) { lastPct = p; percent = p; changed() }
                            }
                        }
                    }
                }
                apkFile(app).delete()
                tmp.renameTo(apkFile(app))
                stage = Stage.READY; percent = 100; changed()
                if (thenInstall) main.post { install(app) }
            } catch (e: Exception) {
                stage = Stage.ERROR; message = "Falhou o download. Tente de novo."; changed()
            }
        }
    }

    /** true se o Android ainda precisa que a pessoa libere "instalar apps desconhecidos" */
    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()

    fun openPermissionScreen(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    /** instala por cima; nas próximas vezes o Android pode nem perguntar */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val f = apkFile(app)
        if (!f.exists()) { download(app, thenInstall = true); return }
        stage = Stage.INSTALLING; changed()
        thread(isDaemon = true) {
            try {
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("app.apk", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(app, 77, Intent(app, InstallReceiver::class.java), flags)
                    s.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                stage = Stage.ERROR; message = "Não consegui instalar: ${e.message}"; changed()
            }
        }
    }

    internal fun onInstallResult(status: Int, msg: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) return
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            stage = Stage.READY
            message = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Atualização cancelada" else "Não instalou ($msg)"
            changed()
        }
    }

    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (_: Exception) {}
                }
            }
            onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
