package it.velo.traduttore.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

object ScreenMetrics {

    /** Dimensioni fisiche reali del display principale (incluse barre di sistema). */
    @Suppress("DEPRECATION")
    fun real(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        display.getRealMetrics(metrics)
        return metrics
    }
}
