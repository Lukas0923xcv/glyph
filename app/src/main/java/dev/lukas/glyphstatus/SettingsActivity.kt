package dev.lukas.glyphstatus

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.lukas.glyphstatus.plugin.Plugin
import dev.lukas.glyphstatus.plugin.PluginData
import dev.lukas.glyphstatus.plugin.PluginEngine
import dev.lukas.glyphstatus.plugin.PluginException
import dev.lukas.glyphstatus.plugin.PluginParser
import dev.lukas.glyphstatus.plugin.PluginRunner
import dev.lukas.glyphstatus.plugin.PluginStore
import dev.lukas.glyphstatus.plugin.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: ConfigStore
    private lateinit var pluginStore: PluginStore
    private lateinit var clockOnlyBox: CheckBox

    private lateinit var host: EditText
    private lateinit var tokenId: EditText
    private lateinit var secret: EditText
    private lateinit var fingerprint: EditText
    private lateinit var result: TextView
    private lateinit var testButton: Button

    /** The stored secret is never put back into the text field. */
    private var savedSecret = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        host = requireViewById(R.id.host)
        tokenId = requireViewById(R.id.token_id)
        secret = requireViewById(R.id.secret)
        fingerprint = requireViewById(R.id.fingerprint)
        result = requireViewById(R.id.result)
        testButton = requireViewById(R.id.test)

        store = ConfigStore(applicationContext)
        val config = store.load()
        host.setText(config.host)
        tokenId.setText(config.tokenId)
        fingerprint.setText(config.fingerprint)
        savedSecret = config.secret
        if (savedSecret.isNotEmpty()) secret.setHint(R.string.hint_secret_saved)

        requireViewById<Button>(R.id.save).setOnClickListener { save() }
        testButton.setOnClickListener { test() }
        requireViewById<Button>(R.id.open_toys).setOnClickListener { openToysManager() }

        setUpDisplaySettings()

        pluginStore = PluginStore(applicationContext)
        requireViewById<Button>(R.id.plugin_import_file).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(intent, REQUEST_PLUGIN_FILE)
        }
        requireViewById<Button>(R.id.plugin_import_clipboard).setOnClickListener {
            val text = getSystemService(ClipboardManager::class.java)?.primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(this, R.string.plugin_clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                installPlugin(text)
            }
        }
        showPlugins()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PLUGIN_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text == null) {
            Toast.makeText(this, R.string.plugin_read_failed, Toast.LENGTH_SHORT).show()
        } else {
            installPlugin(text)
        }
    }

    /** Validates and installs; shows every problem if the plugin is invalid. */
    private fun installPlugin(json: String) {
        val plugin = try {
            PluginParser.parse(json)
        } catch (e: PluginException) {
            AlertDialog.Builder(this)
                .setTitle(R.string.plugin_invalid)
                .setMessage(e.errors.joinToString("\n") { "• $it" })
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        pluginStore.add(plugin)
        Toast.makeText(this, getString(R.string.plugin_installed, plugin.name), Toast.LENGTH_SHORT).show()
        showPlugins()
        if (plugin.secrets.isNotEmpty()) askSecrets(plugin)
    }

    private fun askSecrets(plugin: Plugin) {
        val saved = pluginStore.secrets(plugin)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val fields = plugin.secrets.associate { secret ->
            form.addView(TextView(this).apply { text = secret.label })
            val field = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (!saved[secret.key].isNullOrEmpty()) setHint(R.string.plugin_secret_keep)
            }
            form.addView(field)
            secret.key to field
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugin_secrets_title, plugin.name))
            .setView(form)
            .setPositiveButton(R.string.ok) { _, _ ->
                fields.forEach { (key, field) ->
                    val value = field.text.toString().trim()
                    if (value.isNotEmpty()) pluginStore.setSecret(plugin, key, value)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** One row per installed plugin: on/off, secrets, remove. */
    private fun showPlugins() {
        val list = requireViewById<LinearLayout>(R.id.plugin_list)
        list.removeAllViews()
        for (plugin in pluginStore.plugins()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                CheckBox(this).apply {
                    text = plugin.name
                    isChecked = pluginStore.isEnabled(plugin.id)
                    setOnCheckedChangeListener { _, checked -> pluginStore.setEnabled(plugin.id, checked) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (plugin.secrets.isNotEmpty()) {
                row.addView(Button(this).apply {
                    setText(R.string.plugin_secrets)
                    setOnClickListener { askSecrets(plugin) }
                })
            }
            row.addView(Button(this).apply {
                setText(R.string.plugin_remove)
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setMessage(getString(R.string.plugin_remove_confirm, plugin.name))
                        .setPositiveButton(R.string.plugin_remove) { _, _ ->
                            pluginStore.remove(plugin.id)
                            showPlugins()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
            list.addView(row)
        }
    }

    /** Fetches every enabled plugin once and describes what it got (for "Jetzt testen"). */
    private fun testPlugins(config: ServerConfig, nodes: List<String>): String = buildString {
        for (plugin in pluginStore.enabled()) {
            append("\n\nPlugin „${plugin.name}“: ")
            if (plugin.source == Source.Builtin) {
                append("nutzt die eingebauten Werte")
                continue
            }
            val fetched = PluginRunner.fetch(plugin, pluginStore.secrets(plugin), config, nodes.firstOrNull())
            if (fetched.error != null) {
                append("FEHLER – ${fetched.error}")
                continue
            }
            append(fetched.values.entries.joinToString { (k, v) -> "$k = ${v ?: "–"}" })
            val shown = PluginEngine.render(13, plugin, PluginData(fetched.values, false), true)
            if (shown == null) append(" (zeigt so nichts an: keine Variante passt oder der Text ist zu breit)")
        }
    }

    /** Everything under "Anzeige auf der Matrix"; saved immediately, the running toy picks it up live. */
    private fun setUpDisplaySettings() {
        val displayStore = DisplaySettingsStore(applicationContext)
        var settings = displayStore.load()
        fun update(change: (DisplaySettings) -> DisplaySettings) {
            // Start from what is stored: the tile, launcher entry or Glyph Button may have changed it meanwhile.
            settings = change(displayStore.load())
            displayStore.save(settings)
        }

        val boxes = mapOf(
            Screens.View.UPTIME to R.id.view_uptime,
            Screens.View.GUESTS to R.id.view_guests,
            Screens.View.CPU to R.id.view_cpu,
            Screens.View.MEM to R.id.view_mem,
            Screens.View.LOAD to R.id.view_load,
            Screens.View.DISK to R.id.view_disk,
            Screens.View.NET to R.id.view_net,
            Screens.View.BACKUP to R.id.view_backup,
            Screens.View.UPDATES to R.id.view_updates,
            Screens.View.HEALTH to R.id.view_health,
            Screens.View.QUORUM to R.id.view_quorum,
            Screens.View.CLOCK to R.id.view_clock,
        ).mapValues { (_, id) -> requireViewById<CheckBox>(id) }

        for ((view, box) in boxes) {
            box.isChecked = view in settings.views
            box.setOnCheckedChangeListener { _, checked ->
                val views = if (checked) settings.views + view else settings.views - view
                if (views.isEmpty()) {
                    box.isChecked = true
                    Toast.makeText(this, R.string.need_one_view, Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                update { it.copy(views = views) }
            }
        }

        val designIds = mapOf(
            Screens.View.GUESTS to R.id.design_guests,
            Screens.View.CPU to R.id.design_cpu,
            Screens.View.MEM to R.id.design_mem,
            Screens.View.DISK to R.id.design_disk,
        )
        for ((view, id) in designIds) {
            val choices = DisplaySettings.DESIGN_CHOICES.getValue(view)
            bindSpinner(id, choices.map { getString(designLabel(it)) }, choices.indexOf(settings.design(view))) { i ->
                update { it.copy(designs = it.designs + (view to choices[i])) }
            }
        }

        val intervals = DisplaySettings.INTERVAL_CHOICES
        bindSpinner(R.id.interval, intervals.map { getString(R.string.interval_seconds, it) }, intervals.indexOf(settings.intervalSeconds)) { i ->
            update { it.copy(intervalSeconds = intervals[i]) }
        }

        clockOnlyBox = requireViewById<CheckBox>(R.id.clock_only).apply {
            isChecked = settings.clockOnly
            setOnCheckedChangeListener { _, checked -> update { it.copy(clockOnly = checked) } }
        }

        val brightnessLabel = requireViewById<TextView>(R.id.brightness_label)
        brightnessLabel.text = getString(R.string.brightness_label, settings.brightness)
        requireViewById<SeekBar>(R.id.brightness).apply {
            min = Screens.MIN_BRIGHTNESS
            max = 100
            progress = settings.brightness
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // Saved while dragging so the matrix follows the slider live.
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    brightnessLabel.text = getString(R.string.brightness_label, progress)
                    update { it.copy(brightness = progress) }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        requireViewById<CheckBox>(R.id.brightness_by_load).apply {
            isChecked = settings.brightnessByLoad
            setOnCheckedChangeListener { _, checked -> update { it.copy(brightnessByLoad = checked) } }
        }

        val modes = Screens.AlertMode.entries
        val modeLabels = listOf(R.string.alert_off, R.string.alert_only, R.string.alert_priority).map(::getString)
        bindSpinner(R.id.alert_mode, modeLabels, modes.indexOf(settings.alertMode)) { i ->
            update { it.copy(alertMode = modes[i]) }
        }
        bindPercent(R.id.cpu_alert, settings.cpuAlert) { value -> update { it.copy(cpuAlert = value) } }
        bindPercent(R.id.mem_alert, settings.memAlert) { value -> update { it.copy(memAlert = value) } }
        bindPercent(R.id.disk_alert, settings.diskAlert) { value -> update { it.copy(diskAlert = value) } }

        requireViewById<CheckBox>(R.id.night_enabled).apply {
            isChecked = settings.nightEnabled
            setOnCheckedChangeListener { _, checked -> update { it.copy(nightEnabled = checked) } }
        }
        val hours = (0..23).map { getString(R.string.hour, it) }
        bindSpinner(R.id.night_from, hours, settings.nightFrom) { i -> update { it.copy(nightFrom = i) } }
        bindSpinner(R.id.night_to, hours, settings.nightTo) { i -> update { it.copy(nightTo = i) } }
    }

    private fun designLabel(design: Screens.Design) = when (design) {
        Screens.Design.TEXT -> R.string.design_text
        Screens.Design.RING -> R.string.design_ring
        Screens.Design.HISTORY -> R.string.design_history
        Screens.Design.DOTS -> R.string.design_dots
    }

    /** Fills a spinner and calls [onSelected] when the user picks a different entry. */
    private fun bindSpinner(id: Int, labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        val spinner = requireViewById<Spinner>(id)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        var current = selected.coerceAtLeast(0)
        spinner.setSelection(current)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == current) return
                current = position
                onSelected(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Threshold field accepting 1..100; invalid input is simply not saved. */
    private fun bindPercent(id: Int, value: Int, onChanged: (Int) -> Unit) {
        requireViewById<EditText>(id).apply {
            setText(value.toString())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.takeIf { it in 1..100 }?.let(onChanged)
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        // Clock only may have been toggled from the launcher entry, the tile or the Glyph Button.
        if (::clockOnlyBox.isInitialized) clockOnlyBox.isChecked = DisplaySettingsStore(applicationContext).load().clockOnly
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun currentConfig() = ServerConfig(
        host = host.text.toString().trim(),
        tokenId = tokenId.text.toString().trim(),
        secret = secret.text.toString().trim().ifEmpty { savedSecret },
        fingerprint = fingerprint.text.toString().trim(),
    )

    /** Returns an error message, or null if the config is usable. */
    private fun validate(config: ServerConfig): String? = when {
        !config.host.startsWith("https://") && !config.host.startsWith("http://") ->
            "Host-URL muss mit https:// beginnen"
        config.tokenId.isEmpty() -> "Token-ID fehlt"
        config.secret.isEmpty() -> "Token-Secret fehlt"
        config.fingerprint.isNotEmpty() && Fingerprint.parse(config.fingerprint) == null ->
            "Fingerprint muss 64 Hex-Zeichen (SHA-256) haben"
        else -> null
    }

    private fun save() {
        val config = currentConfig()
        validate(config)?.let {
            result.text = it
            return
        }
        store.save(config)
        savedSecret = config.secret
        secret.text.clear()
        secret.setHint(R.string.hint_secret_saved)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun test() {
        val config = currentConfig()
        validate(config)?.let {
            result.text = it
            return
        }
        result.setText(R.string.testing)
        testButton.isEnabled = false
        scope.launch {
            val check = withContext(Dispatchers.IO) { ProxmoxClient.check(config) }
            val pluginReport = withContext(Dispatchers.IO) { testPlugins(config, check.nodes) }
            result.text = getString(if (check.up) R.string.result_up else R.string.result_down, check.message) + pluginReport
            testButton.isEnabled = true
        }
    }

    /** Opens the system "Manage Glyph Toys" screen, as recommended by the Glyph Matrix SDK. */
    private fun openToysManager() {
        val intent = Intent().setComponent(
            ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity")
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toys_manager_missing, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQUEST_PLUGIN_FILE = 1
    }
}
