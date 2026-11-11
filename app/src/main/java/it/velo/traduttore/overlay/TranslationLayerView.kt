package it.velo.traduttore.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

/**
 * Livello trasparente a tutto schermo che disegna le traduzioni sopra il testo originale.
 * Prima tutte le coperture (sfondo), poi tutto il testo: così una copertura non taglia mai
 * le lettere di una riga vicina. Le coordinate sono in pixel dello schermo fisico.
 */
@SuppressLint("ViewConstructor")
class TranslationLayerView(context: Context) : View(context) {

    private val radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val location = IntArray(2)
    private var blocks: List<RenderedBlock> = emptyList()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setBlocks(items: List<RenderedBlock>) {
        recyclePatches()
        blocks = items
        invalidate()
    }

    fun clear() {
        if (blocks.isEmpty()) return
        recyclePatches()
        blocks = emptyList()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        recyclePatches()
        blocks = emptyList()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty()) return
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        for (block in blocks) {
            for (cover in block.covers) {
                val patch = cover.patch
                if (patch != null && !patch.isRecycled) {
                    canvas.drawBitmap(patch, null, cover.rect, patchPaint)
                } else {
                    fill.color = cover.color
                    canvas.drawRoundRect(cover.rect, radius, radius, fill)
                }
            }
        }
        for (block in blocks) {
            for (line in block.lines) {
                canvas.drawText(line.text, line.x, line.baseline, block.paint)
            }
        }
        canvas.restore()
    }

    private fun recyclePatches() {
        for (block in blocks) {
            for (cover in block.covers) cover.patch?.recycle()
        }
    }
}
