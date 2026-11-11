package it.velo.traduttore.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import it.velo.traduttore.Prefs
import it.velo.traduttore.R
import it.velo.traduttore.capture.ScreenMetrics
import kotlin.math.hypot

/**
 * Gestisce le due finestre sovrapposte di Velo:
 *  - il livello delle traduzioni (a tutto schermo, non riceve tocchi);
 *  - la bolla flottante con l'eventuale messaggio di stato.
 *
 * Tutti i metodi vanno chiamati sul thread principale.
 */
class OverlayController(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks,
    /**
     * true quando le finestre appartengono al servizio di Accessibilità: in quel caso possono
     * essere completamente opache senza bloccare i tocchi sull'app sottostante.
     */
    private val accessibilityOverlay: Boolean = false,
) {

    private val windowType: Int = if (accessibilityOverlay) {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    interface Callbacks {
        fun onBubbleTap()
        fun onBubbleLongPress()
        fun onBubbleMoved()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val dm = context.resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

    private var layer: TranslationLayerView? = null
    private var bubbleRoot: LinearLayout? = null
    private var bubble: BubbleButton? = null
    private var label: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** Area occupata dalla bolla, in coordinate schermo. Letta dal thread di cattura. */
    @Volatile
    var bubbleBounds: Rect? = null
        private set

    val isAttached: Boolean get() = layer != null

    /** @throws WindowManager.BadTokenException se manca il permesso di sovrapposizione */
    fun attach() {
        if (layer != null) return
        val layerView = TranslationLayerView(context)
        wm.addView(layerView, layerParams())
        layer = layerView
        try {
            attachBubble()
        } catch (e: RuntimeException) {
            detach()
            throw e
        }
    }

    fun detach() {
        layer?.let { safeRemove(it) }
        layer = null
        bubbleRoot?.let { safeRemove(it) }
        bubbleRoot = null
        bubble = null
        label = null
        bubbleParams = null
        bubbleBounds = null
    }

    fun show(blocks: List<RenderedBlock>) {
        layer?.setBlocks(blocks)
    }

    fun clear() {
        layer?.clear()
    }

    fun setPaused(paused: Boolean) {
        bubble?.state = if (paused) BubbleButton.State.PAUSED else BubbleButton.State.ACTIVE
    }

    fun setWorking(working: Boolean) {
        bubble?.working = working
    }

    fun setStatus(message: String?) {
        val tv = label ?: return
        if (message.isNullOrBlank()) {
            tv.visibility = View.GONE
        } else {
            tv.text = message
            tv.visibility = View.VISIBLE
        }
    }

    /** Da chiamare dopo una rotazione: riporta la bolla dentro lo schermo. */
    fun onScreenSizeChanged() {
        val root = bubbleRoot ?: return
        val lp = bubbleParams ?: return
        val screenH = ScreenMetrics.real(context).heightPixels
        lp.y = (prefs.bubbleY * screenH).toInt().coerceIn(0, maxOf(0, screenH - bubbleSize()))
        update(root, lp)
    }

    // ---------------------------------------------------------------------------------------

    private fun layerParams(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.title = "Velo - traduzioni"
        applyFullScreenLayout(lp)
        if (!accessibilityOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Da Android 12 i tocchi attraversano una finestra sovrapposta solo se la sua
            // opacità non supera questo limite (di norma 0.8): altrimenti l'app sotto si bloccherebbe.
            val im = context.getSystemService(InputManager::class.java)
            lp.alpha = im.maximumObscuringOpacityForTouch
        }
        return lp
    }

    private fun applyFullScreenLayout(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun bubbleSize() = dp(60f).toInt()
    private fun edgeMargin() = dp(6f).toInt()

    private fun attachBubble() {
        val button = BubbleButton(context)
        val text = TextView(context).apply {
            visibility = View.GONE
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220f).toInt()
            setPadding(dp(12f).toInt(), dp(7f).toInt(), dp(12f).toInt(), dp(7f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(ContextCompat.getColor(context, R.color.bubble_fill))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button)
            addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(4f).toInt()
                marginEnd = dp(4f).toInt()
            })
        }

        val screenH = ScreenMetrics.real(context).heightPixels
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.title = "Velo - bolla"
        applyFullScreenLayout(lp)
        lp.y = (prefs.bubbleY * screenH).toInt().coerceIn(0, maxOf(0, screenH - bubbleSize()))
        applySide(root, lp, prefs.bubbleOnRight)

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishBounds() }
        button.setOnClickListener { callbacks.onBubbleTap() }
        button.setOnLongClickListener {
            callbacks.onBubbleLongPress()
            true
        }
        attachTouchHandling(button, root, lp)

        bubbleRoot = root
        bubble = button
        label = text
        bubbleParams = lp
        wm.addView(root, lp)
        publishBounds()
    }

    /** A destra la bolla è ancorata al bordo destro e il messaggio compare alla sua sinistra. */
    private fun applySide(root: LinearLayout, lp: WindowManager.LayoutParams, right: Boolean) {
        lp.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
        lp.x = edgeMargin()
        root.layoutDirection = if (right) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandling(button: BubbleButton, root: LinearLayout, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callbacks.onBubbleLongPress()
        }

        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = absoluteLeft(root, lp)
                    startTop = lp.y
                    dragging = false
                    longPressed = false
                    button.postDelayed(longPress, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop && !longPressed) {
                        dragging = true
                        button.removeCallbacks(longPress)
                        // Durante il trascinamento usiamo coordinate assolute da sinistra.
                        lp.gravity = Gravity.TOP or Gravity.START
                        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
                    }
                    if (dragging) {
                        val metrics = ScreenMetrics.real(context)
                        lp.x = (startLeft + dx).toInt().coerceIn(0, maxOf(0, metrics.widthPixels - root.width))
                        lp.y = (startTop + dy).toInt().coerceIn(0, maxOf(0, metrics.heightPixels - root.height))
                        update(root, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    button.removeCallbacks(longPress)
                    if (dragging) {
                        snapToEdge(root, lp)
                    } else if (!longPressed) {
                        button.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    button.removeCallbacks(longPress)
                    if (dragging) snapToEdge(root, lp)
                    true
                }
                else -> false
            }
        }
    }

    private fun absoluteLeft(root: View, lp: WindowManager.LayoutParams): Int {
        val isRight = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END ||
            (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
        if (!isRight) return lp.x
        val screenW = ScreenMetrics.real(context).widthPixels
        return screenW - lp.x - root.width
    }

    private fun snapToEdge(root: LinearLayout, lp: WindowManager.LayoutParams) {
        val metrics = ScreenMetrics.real(context)
        val centerX = lp.x + bubbleSize() / 2
        val right = centerX > metrics.widthPixels / 2
        applySide(root, lp, right)
        update(root, lp)
        prefs.bubbleOnRight = right
        prefs.bubbleY = if (metrics.heightPixels > 0) lp.y.toFloat() / metrics.heightPixels else 0.4f
        callbacks.onBubbleMoved()
    }

    private fun update(root: View, lp: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(root, lp)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bolla non più collegata", e)
        }
        publishBounds()
    }

    /**
     * Calcola l'area della bolla dai parametri della finestra (più affidabile della posizione
     * riportata dalla vista mentre la finestra si sta spostando). Un margine ampio copre
     * l'ombra e piccoli ritardi di aggiornamento.
     */
    private fun publishBounds() {
        val root = bubbleRoot ?: return
        val lp = bubbleParams ?: return
        val w = if (root.width > 0) root.width else bubbleSize()
        val h = if (root.height > 0) root.height else bubbleSize()
        val left = absoluteLeft(root, lp)
        val pad = dp(16f).toInt()
        bubbleBounds = Rect(left - pad, lp.y - pad, left + w + pad, lp.y + h + pad)
    }

    private fun safeRemove(view: View) {
        try {
            wm.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // già rimossa
        }
    }

    private companion object {
        const val TAG = "VeloOverlay"
    }
}
