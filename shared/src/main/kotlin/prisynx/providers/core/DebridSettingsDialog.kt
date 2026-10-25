package prisynx.providers.core

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.runBlocking

/**
 * Debrid settings for the stand-alone IlCorsaroViola extension.
 *
 * The hub shows the same values in its "Debrid" tab; both read and write
 * [DebridSettings], so a key entered in one place is immediately visible in the
 * other. The key is stored in the app-private SharedPreferences and never
 * shipped with the plugin.
 */
object DebridSettingsDialog {
    fun show(context: Context) {
        DebridSettings.attach(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        content.addView(TextView(context).apply {
            text = "Con un servizio debrid i magnet di IlCorsaroViola diventano link " +
                "diretti, ma solo per i torrent già presenti in cache sul servizio: " +
                "gli altri restano magnet. La chiave resta salvata sul dispositivo."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

        val enabled = CheckBox(context).apply {
            text = "Usa il servizio debrid"
            isChecked = DebridSettings.enabled
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(enabled)

        val group = RadioGroup(context)
        DebridSettings.services.forEach { service ->
            val button = RadioButton(context).apply {
                id = android.view.View.generateViewId()
                text = "${service.label}  (${service.keyPage})"
                isChecked = service.id == DebridSettings.service
                setPadding(0, dp(4), 0, dp(4))
            }
            button.tag = service.id
            group.addView(button)
        }
        content.addView(group)

        val keyField = EditText(context).apply {
            setText(DebridSettings.apiKey)
            hint = "Chiave API del servizio selezionato"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        content.addView(
            keyField,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        val verify = Button(context).apply {
            text = "Verifica chiave"
            setOnClickListener {
                val typed = keyField.text.toString().trim()
                if (typed.isEmpty()) {
                    Toast.makeText(context, "Inserisci la chiave", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                text = "Verifica in corso..."
                Thread {
                    val message = runBlocking {
                        Debrid.check(selectedService(group), typed)
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        isEnabled = true
                        text = "Verifica chiave"
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        content.addView(verify)

        val scroll = ScrollView(context).apply { addView(content) }

        AlertDialog.Builder(context)
            .setTitle("IlCorsaroViola · Debrid")
            .setView(scroll)
            .setPositiveButton("Salva") { _, _ ->
                DebridSettings.enabled = enabled.isChecked
                DebridSettings.service = selectedService(group)
                DebridSettings.setApiKey(keyField.text.toString())
                Debrid.clearCache()
                Toast.makeText(context, "Impostazioni debrid salvate", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Elimina chiave") { _, _ ->
                DebridSettings.setApiKey("")
                Debrid.clearCache()
                Toast.makeText(context, "Chiave eliminata", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun selectedService(group: RadioGroup): String {
        val checked = group.checkedRadioButtonId
        if (checked == -1) return DebridSettings.service
        val button = group.findViewById<RadioButton>(checked)
        return button?.tag as? String ?: DebridSettings.service
    }
}
