package it.velo.traduttore.pipeline

import android.graphics.Bitmap

/** Fonte dei fotogrammi dello schermo (MediaProjection oppure screenshot di Accessibilità). */
interface FrameSource {
    val width: Int
    val height: Int

    /** Ultimo fotogramma; può essere più largo di [width] (padding delle righe). */
    val frame: Bitmap?

    /**
     * true se i fotogrammi arrivano in continuo e controllarli costa poco (MediaProjection);
     * false se ogni cattura è una richiesta esplicita e limitata nel tempo (Accessibilità).
     */
    val continuous: Boolean

    /**
     * Aggiorna [frame]. Restituisce true se ora c'è un fotogramma nuovo; false se lo schermo
     * non è cambiato (fonte continua) o se la cattura non è riuscita.
     */
    suspend fun capture(): Boolean

    /** Copia indipendente del fotogramma corrente, alle dimensioni reali. */
    fun snapshot(): Bitmap?

    /** Rotazione o cambio risoluzione. */
    fun onDisplayChanged()

    fun release()
}
