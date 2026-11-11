package it.velo.traduttore.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/** Riceve l'esito dell'installazione di un aggiornamento. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android chiede conferma all'utente ("Vuoi aggiornare questa app?").
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Log.w(TAG, "Impossibile mostrare la conferma", e)
                        UpdateManager.set(UpdateManager.State.Failed("Apri Velo e riprova l'aggiornamento"))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.set(UpdateManager.State.UpToDate)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateManager.set(UpdateManager.State.Idle)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Installazione non riuscita: $status $message")
                UpdateManager.set(UpdateManager.State.Failed("Installazione non riuscita ($status)"))
            }
        }
    }

    companion object {
        const val ACTION = "it.velo.traduttore.action.INSTALL_RESULT"
        private const val TAG = "VeloUpdate"
    }
}
