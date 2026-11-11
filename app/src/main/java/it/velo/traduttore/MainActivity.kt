package it.velo.traduttore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import it.velo.traduttore.databinding.ActivityMainBinding
import it.velo.traduttore.ocr.Script
import it.velo.traduttore.service.TranslateService
import it.velo.traduttore.service.VeloAccessibilityService
import it.velo.traduttore.update.UpdateManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var updates: UpdateManager

    private val sourceCodes: List<String> by lazy { listOf(Prefs.AUTO) + Languages.sourceOptions() }
    private var downloadedModels: List<String> = emptyList()
    private var pendingInstall: UpdateManager.UpdateInfo? = null

    private val chipByScript by lazy {
        mapOf(
            Script.LATIN to b.chipLatin,
            Script.CHINESE to b.chipChinese,
            Script.JAPANESE to b.chipJapanese,
            Script.KOREAN to b.chipKorean,
            Script.DEVANAGARI to b.chipDevanagari,
        )
    }

    private val chipByEngine by lazy {
        mapOf(
            EngineKind.OFFLINE to b.chipOffline,
            EngineKind.DEEPL to b.chipDeepl,
            EngineKind.CLAUDE to b.chipClaude,
        )
    }

    private val accessibilitySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                TranslateService.start(this, result.resultCode, data)
            } else {
                toast(R.string.capture_denied)
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        updates = UpdateManager(this)

        applyInsets()
        setupStatus()
        setupMode()
        setupPermissions()
        setupEngine()
        setupSourceLanguage()
        setupScripts()
        setupMobileData()
        setupModels()
        setupUpdates()
        setupHelp()
        b.versionText.text = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(VeloState.phase, VeloState.status) { phase, status -> phase to status }
                        .collect { (phase, status) -> renderState(phase, status) }
                }
                launch {
                    UpdateManager.state.collect { renderUpdate(it) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshMode()
        refreshModels()
        autoCheckUpdates()
    }

    // --- Stato e avvio ---------------------------------------------------------------------

    private fun setupStatus() {
        b.mainButton.setOnClickListener {
            when (VeloState.phase.value) {
                VeloState.Phase.OFF -> requestStart()
                VeloState.Phase.STARTING -> Unit
                VeloState.Phase.ACTIVE, VeloState.Phase.PAUSED -> stopTranslation()
            }
        }
    }

    private fun requestStart() {
        if (!Settings.canDrawOverlays(this) && !accessibilityActive()) {
            toast(R.string.overlay_needed)
            openOverlaySettings()
            return
        }
        if (accessibilityActive()) {
            VeloAccessibilityService.instance?.startTranslating()
            return
        }
        if (accessibilitySupported && VeloAccessibilityService.isEnabled(this)) {
            toast(R.string.mode_starting)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Forza la condivisione dell'intero schermo: con una singola app le coordinate non tornerebbero.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun stopTranslation() {
        if (VeloState.mode.value == VeloState.Mode.ACCESSIBILITY) {
            VeloAccessibilityService.instance?.stopTranslating()
        } else {
            TranslateService.stop(this)
        }
    }

    private fun accessibilityActive(): Boolean =
        accessibilitySupported && VeloAccessibilityService.instance != null

    private fun renderState(phase: VeloState.Phase, status: String?) {
        val (title, detail, dotColor) = when (phase) {
            VeloState.Phase.OFF -> Triple(R.string.state_off, R.string.state_off_detail, R.color.velo_off)
            VeloState.Phase.STARTING -> Triple(R.string.state_starting, R.string.state_off_detail, R.color.velo_warn)
            VeloState.Phase.ACTIVE -> Triple(R.string.state_active, R.string.state_active_detail, R.color.velo_ok)
            VeloState.Phase.PAUSED -> Triple(R.string.state_paused, R.string.state_paused_detail, R.color.velo_warn)
        }
        b.statusTitle.setText(title)
        b.statusDetail.setText(
            if (phase == VeloState.Phase.OFF && accessibilityActive()) R.string.state_off_detail_a11y else detail,
        )
        b.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))
        b.statusMessage.text = status
        b.statusMessage.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
        b.mainButton.setText(if (phase == VeloState.Phase.OFF) R.string.action_start else R.string.action_stop)
        b.mainButton.isEnabled = phase != VeloState.Phase.STARTING
        if (phase == VeloState.Phase.OFF) refreshModels()
    }

    // --- Modalità precisa ------------------------------------------------------------------

    private fun setupMode() {
        b.modeOpenButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.modeUnlockButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun refreshMode() {
        if (!accessibilitySupported) {
            b.modeTitle.setText(R.string.mode_title_off)
            b.modeDesc.setText(R.string.mode_unsupported)
            b.modeRestricted.visibility = View.GONE
            b.modeButtons.visibility = View.GONE
            b.modeDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.velo_off))
            return
        }
        val enabled = VeloAccessibilityService.isEnabled(this)
        b.modeTitle.setText(if (enabled) R.string.mode_title_on else R.string.mode_title_off)
        b.modeDesc.setText(R.string.mode_desc)
        b.modeRestricted.visibility = if (enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) View.GONE else View.VISIBLE
        b.modeUnlockButton.visibility = b.modeRestricted.visibility
        b.modeButtons.visibility = if (enabled) View.GONE else View.VISIBLE
        b.modeDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (enabled) R.color.velo_ok else R.color.velo_warn),
        )
    }

    // --- Permessi --------------------------------------------------------------------------

    private fun setupPermissions() {
        b.overlayButton.setOnClickListener { openOverlaySettings() }
        b.notifButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        b.notifRow.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            overlayLauncher.launch(intent)
        } catch (e: Exception) {
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun refreshPermissions() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        renderPermissionButton(b.overlayButton, overlayOk)
        renderPermissionButton(b.notifButton, notifOk)
        b.permissionsCard.visibility = if (overlayOk && notifOk) View.GONE else View.VISIBLE
    }

    private fun renderPermissionButton(button: MaterialButton, granted: Boolean) {
        button.setText(if (granted) R.string.perm_granted else R.string.perm_grant)
        button.isEnabled = !granted
    }

    // --- Motore di traduzione --------------------------------------------------------------

    private fun setupEngine() {
        chipByEngine[prefs.engine]?.isChecked = true
        b.engineChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val engine = chipByEngine.entries.firstOrNull { it.value.id == id }?.key ?: return@setOnCheckedStateChangeListener
            prefs.engine = engine
            renderEngine()
        }
        b.engineKeyInput.doAfterTextChanged { text ->
            when (prefs.engine) {
                EngineKind.DEEPL -> prefs.deeplKey = text?.toString().orEmpty()
                EngineKind.CLAUDE -> prefs.claudeKey = text?.toString().orEmpty()
                EngineKind.OFFLINE -> Unit
            }
        }
        renderEngine()
    }

    private fun renderEngine() {
        val engine = prefs.engine
        b.engineDesc.setText(
            when (engine) {
                EngineKind.OFFLINE -> R.string.engine_offline_desc
                EngineKind.DEEPL -> R.string.engine_deepl_desc
                EngineKind.CLAUDE -> R.string.engine_claude_desc
            },
        )
        b.engineKeyLayout.visibility = if (engine == EngineKind.OFFLINE) View.GONE else View.VISIBLE
        val key = when (engine) {
            EngineKind.DEEPL -> prefs.deeplKey
            EngineKind.CLAUDE -> prefs.claudeKey
            EngineKind.OFFLINE -> ""
        }
        if (b.engineKeyInput.text?.toString() != key) b.engineKeyInput.setText(key)
    }

    // --- Lingua e alfabeto -----------------------------------------------------------------

    private fun setupSourceLanguage() {
        val names = sourceCodes.map { code ->
            if (code == Prefs.AUTO) getString(R.string.setting_source_auto) else Languages.displayName(code)
        }
        b.sourceDropdown.setSimpleItems(names.toTypedArray())
        val current = sourceCodes.indexOf(prefs.sourceLanguage).coerceAtLeast(0)
        b.sourceDropdown.setText(names[current], false)
        b.sourceDropdown.setOnItemClickListener { _, _, position, _ ->
            prefs.sourceLanguage = sourceCodes[position]
            updateScriptVisibility()
        }
    }

    private fun setupScripts() {
        chipByScript[prefs.autoScript]?.isChecked = true
        b.scriptChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            chipByScript.entries.firstOrNull { it.value.id == id }?.let { prefs.autoScript = it.key }
        }
        updateScriptVisibility()
    }

    private fun updateScriptVisibility() {
        b.scriptSection.visibility = if (prefs.sourceLanguage == Prefs.AUTO) View.VISIBLE else View.GONE
    }

    private fun setupMobileData() {
        b.mobileDataSwitch.isChecked = prefs.allowMobileData
        b.mobileDataSwitch.setOnCheckedChangeListener { _, checked -> prefs.allowMobileData = checked }
    }

    // --- Pacchetti lingua ------------------------------------------------------------------

    private fun setupModels() {
        b.manageModelsButton.setOnClickListener { showDeleteModelsDialog() }
    }

    private fun refreshModels() {
        lifecycleScope.launch {
            val codes = try {
                RemoteModelManager.getInstance()
                    .getDownloadedModels(TranslateRemoteModel::class.java).await()
                    .map { it.language }
            } catch (e: Exception) {
                emptyList()
            }
            // Inglese è integrato e l'italiano è la lingua di arrivo: non vanno elencati.
            downloadedModels = codes
                .filter { it != TranslateLanguage.ENGLISH && it != TranslateLanguage.ITALIAN }
                .sortedBy { Languages.displayName(it) }
            if (downloadedModels.isEmpty()) {
                b.modelsText.setText(R.string.models_none)
                b.manageModelsButton.visibility = View.GONE
            } else {
                val list = downloadedModels.joinToString(", ") { Languages.displayName(it) }
                b.modelsText.text = getString(R.string.models_list, list)
                b.manageModelsButton.visibility = View.VISIBLE
            }
        }
    }

    private fun showDeleteModelsDialog() {
        val codes = downloadedModels
        if (codes.isEmpty()) return
        val names = codes.map { Languages.displayName(it) }.toTypedArray()
        val checked = BooleanArray(codes.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.models_dialog_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.models_cancel, null)
            .setPositiveButton(R.string.models_delete) { _, _ ->
                val selected = codes.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val manager = RemoteModelManager.getInstance()
                    for (code in selected) {
                        try {
                            manager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
                        } catch (_: Exception) {
                        }
                    }
                    toast(R.string.models_deleted)
                    refreshModels()
                }
            }
            .show()
    }

    // --- Aggiornamenti ---------------------------------------------------------------------

    private fun setupUpdates() {
        b.updateVersion.text = getString(R.string.update_current, BuildConfig.VERSION_NAME)
        b.updateButton.setOnClickListener {
            when (val state = UpdateManager.state.value) {
                is UpdateManager.State.Available -> startUpdate(state.info)
                is UpdateManager.State.Downloading, UpdateManager.State.Installing, UpdateManager.State.Checking -> Unit
                else -> checkUpdates()
            }
        }
        if (!updates.isConfigured) {
            b.updateStatus.setText(R.string.update_not_configured)
            b.updateButton.visibility = View.GONE
        }
    }

    private fun autoCheckUpdates() {
        if (!updates.isConfigured) return
        // Se l'utente era andato a concedere il permesso di installazione, riprendiamo da lì.
        pendingInstall?.let { info ->
            if (updates.canInstall()) {
                pendingInstall = null
                startUpdate(info)
                return
            }
        }
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 6 * 60 * 60 * 1000L) return
        if (UpdateManager.state.value !is UpdateManager.State.Idle) return
        checkUpdates()
    }

    private fun checkUpdates() {
        prefs.lastUpdateCheck = System.currentTimeMillis()
        lifecycleScope.launch { updates.check() }
    }

    private fun startUpdate(info: UpdateManager.UpdateInfo) {
        if (!updates.canInstall()) {
            pendingInstall = info
            toast(R.string.update_need_permission)
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        lifecycleScope.launch { updates.downloadAndInstall(info) }
    }

    private fun renderUpdate(state: UpdateManager.State) {
        if (!updates.isConfigured) return
        b.updateProgress.visibility = View.GONE
        b.updateButton.isEnabled = true
        when (state) {
            UpdateManager.State.Idle -> {
                b.updateStatus.setText(R.string.update_keep_data)
                b.updateButton.setText(R.string.update_check)
            }
            UpdateManager.State.Checking -> {
                b.updateStatus.setText(R.string.update_checking)
                b.updateButton.isEnabled = false
            }
            UpdateManager.State.UpToDate -> {
                b.updateStatus.setText(R.string.update_uptodate)
                b.updateButton.setText(R.string.update_check)
            }
            is UpdateManager.State.Available -> {
                val notes = state.info.notes.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                b.updateStatus.text = getString(R.string.update_available, state.info.versionName) + notes
                b.updateButton.setText(R.string.update_install)
            }
            is UpdateManager.State.Downloading -> {
                val pct = (state.progress * 100).toInt().coerceIn(0, 100)
                b.updateStatus.text = getString(R.string.update_downloading, pct)
                b.updateProgress.visibility = View.VISIBLE
                b.updateProgress.setProgressCompat(pct, true)
                b.updateButton.isEnabled = false
            }
            UpdateManager.State.Installing -> {
                b.updateStatus.setText(R.string.update_installing)
                b.updateButton.isEnabled = false
            }
            is UpdateManager.State.Failed -> {
                b.updateStatus.text = getString(R.string.update_failed, state.message)
                b.updateButton.setText(R.string.update_check)
            }
        }
    }

    // --- Guida -----------------------------------------------------------------------------

    private fun setupHelp() {
        val tips = listOf(R.string.help_1, R.string.help_2, R.string.help_3, R.string.help_4)
        val accent = ContextCompat.getColor(this, R.color.velo_accent)
        tips.forEachIndexed { index, res ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val number = TextView(this).apply {
                text = String.format("%02d", index + 1)
                typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.TOP
                minWidth = dp(34)
            }
            val body = TextView(this).apply {
                setText(res)
                setTextAppearance(R.style.Velo_Text_Body)
            }
            row.addView(number)
            row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            b.helpList.addView(row)
        }
    }

    // --- Utilità ---------------------------------------------------------------------------

    private fun applyInsets() {
        val content = b.content
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = baseTop + bars.top, bottom = baseBottom + bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }
}
