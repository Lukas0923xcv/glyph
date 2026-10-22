package dev.lukas.glyphstatus

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: ConfigStore

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
    }

    /** View selection and rotation interval; saved immediately, the toy picks them up live. */
    private fun setUpDisplaySettings() {
        val displayStore = DisplaySettingsStore(applicationContext)
        var settings = displayStore.load()

        val boxes = mapOf(
            Screens.View.UPTIME to R.id.view_uptime,
            Screens.View.GUESTS to R.id.view_guests,
            Screens.View.CPU to R.id.view_cpu,
            Screens.View.MEM to R.id.view_mem,
            Screens.View.DISK to R.id.view_disk,
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
                settings = settings.copy(views = views)
                displayStore.save(settings)
            }
        }

        val choices = DisplaySettings.INTERVAL_CHOICES
        val interval = requireViewById<Spinner>(R.id.interval)
        interval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            choices.map { getString(R.string.interval_seconds, it) },
        )
        interval.setSelection(choices.indexOf(settings.intervalSeconds).coerceAtLeast(0))
        interval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (choices[position] == settings.intervalSeconds) return
                settings = settings.copy(intervalSeconds = choices[position])
                displayStore.save(settings)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
            result.text = getString(if (check.up) R.string.result_up else R.string.result_down, check.message)
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
}
