package com.geanalves.transmiplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.PrintWriter
import java.net.Socket
import java.time.Duration

/** Gera prints das telas (só pra conferir o visual). Rode com -Dshots=1 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("shots.dir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun screens() {
        if (System.getProperty("shots").isNullOrEmpty()) return
        val ctrl = Robolectric.buildActivity(MainActivity::class.java,
            android.content.Intent(android.content.Intent.ACTION_MAIN)).setup()
        val a = ctrl.get()
        val root = a.window.decorView.rootView
        idle(200)
        shot(root, "0_abertura")
        idle(2500)
        // TVs de mentira se conectando
        val tvs = listOf("TV da Sala", "TV do Quarto", "TV da Cozinha").map { n ->
            Socket("127.0.0.1", Proto.CONTROL_PORT).also { s ->
                PrintWriter(s.getOutputStream(), true).println("{\"t\":\"hello\",\"name\":\"$n\"}")
            }
        }
        Thread.sleep(600); idle(300)
        shot(root, "1_inicio")
        a.findViewById<View>(android.R.id.content)
        val m = MainActivity::class.java.getDeclaredMethod("selectTab", Int::class.java).apply { isAccessible = true }
        m.invoke(a, 3); idle(300); shot(root, "3_config")
        Hub.playItem(Hub.Item("media", "https://x/filme.m3u8", "O Guerreiro das Montanhas", mime = "m3u8"))
        Thread.sleep(400); idle(1600)
        m.invoke(a, 2); idle(600); shot(root, "2_controle")
        val b = Robolectric.buildActivity(BrowserActivity::class.java).setup().get()
        idle(500); shot(b.window.decorView.rootView, "4_navegador")
        tvs.forEach { it.close() }
    }
}
