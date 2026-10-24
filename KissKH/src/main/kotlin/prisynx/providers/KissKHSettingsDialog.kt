package prisynx.providers

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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.runBlocking

/**
 * Minimal settings UI for the KissKH extension.
 *
 * It deliberately uses only platform widgets: the extension does not ship any
 * resources, and the DeepL key must stay on the device. The app exposes this
 * dialog through [com.lagradost.cloudstream3.plugins.Plugin.openSettings].
 */
internal object KissKHSettingsDialog {
    fun show(context: Context) {
        KissKHSettings.attach(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun addLabel(text: String) {
            content.addView(
                TextView(context).apply {
                    this.text = text
                    textSize = 13f
                    setPadding(0, dp(10), 0, dp(2))
                }
            )
        }

        fun addField(value: String, hint: String, password: Boolean = false): EditText =
            EditText(context).apply {
                setText(value)
                this.hint = hint
                isSingleLine = true
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                content.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        fun addCheck(text: String, checked: Boolean): CheckBox =
            CheckBox(context).apply {
                this.text = text
                isChecked = checked
                setPadding(0, dp(6), 0, 0)
                content.addView(this)
            }

        content.addView(
            TextView(context).apply {
                text = "Senza chiave DeepL vengono mostrati solo i sottotitoli nativi " +
                    "ITA/ENG. La chiave resta salvata sul dispositivo e non viene mai " +
                    "inclusa nel plugin. Le chiavi gratuite terminano con \":fx\"."
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, dp(4))
            }
        )

        addLabel("URL KissKH")
        val baseField = addField(
            KissKHSettings.baseUrl,
            KissKHSettings.DEFAULT_BASE
        )

        addLabel("Chiave API DeepL (opzionale)")
        val keyField = addField(
            KissKHSettings.deeplKey,
            "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx",
            password = true
        )

        val verifyButton = Button(context).apply {
            text = "Verifica chiave"
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                val typed = keyField.text.toString().trim()
                if (typed.isEmpty()) {
                    Toast.makeText(context, "Inserisci la chiave DeepL", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                text = "Verifica in corso..."
                Thread {
                    val message = runBlocking { KissKHSubtitle.checkKey(typed) }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        isEnabled = true
                        text = "Verifica chiave"
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        content.addView(
            verifyButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        addLabel("Lingua di traduzione")
        val targetField = addField(
            KissKHSettings.targetLang,
            "it"
        )

        addLabel("Relay Cloudflare (opzionale)")
        val proxyField = addField(
            KissKHSettings.proxyTemplate,
            "https://tuo-relay.example/?url={url}"
        )

        addLabel("Traduzione automatica DeepL")
        var translationEnabled = KissKHSettings.translationEnabled
        val translationButton = Button(context).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            text = translationLabel(translationEnabled)
            setOnClickListener {
                translationEnabled = !translationEnabled
                text = translationLabel(translationEnabled)
                if (translationEnabled && keyField.text.toString().trim().isEmpty()) {
                    Toast.makeText(
                        context,
                        "Inserisci la chiave DeepL per attivare la traduzione",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        content.addView(
            translationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val adultBox = addCheck(
            "Filtra i contenuti per adulti",
            KissKHSettings.adultFilter
        )

        val scroll = ScrollView(context).apply {
            addView(content)
        }

        AlertDialog.Builder(context)
            .setTitle("KissKH")
            .setView(scroll)
            .setPositiveButton("Salva") { _, _ ->
                save(context, baseField, keyField, targetField, proxyField, translationEnabled, adultBox)
            }
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Ripristina") { _, _ -> reset(context) }
            .show()
    }

    private fun translationLabel(enabled: Boolean): String =
        if (enabled) "Traduzione DeepL: attiva" else "Traduzione DeepL: disattivata"

    private fun save(
        context: Context,
        baseField: EditText,
        keyField: EditText,
        targetField: EditText,
        proxyField: EditText,
        translationEnabled: Boolean,
        adultBox: CheckBox
    ) {
        val base = baseField.text.toString().trim()
        KissKHSettings.baseUrl = when {
            base.isEmpty() -> KissKHSettings.DEFAULT_BASE
            base.startsWith("http://", ignoreCase = true) ||
                base.startsWith("https://", ignoreCase = true) -> base
            else -> "https://$base"
        }
        KissKHSettings.deeplKey = keyField.text.toString().trim()
        KissKHSettings.targetLang = KissKHText.sanitizeTargetLang(targetField.text.toString()) ?: "it"
        KissKHSettings.proxyTemplate = proxyField.text.toString().trim()
        KissKHSettings.translationEnabled = translationEnabled
        KissKHSettings.adultFilter = adultBox.isChecked
        Toast.makeText(context, "Impostazioni KissKH salvate", Toast.LENGTH_SHORT).show()
    }

    private fun reset(context: Context) {
        KissKHSettings.baseUrl = KissKHSettings.DEFAULT_BASE
        KissKHSettings.deeplKey = ""
        KissKHSettings.targetLang = "it"
        KissKHSettings.proxyTemplate = ""
        KissKHSettings.translationEnabled = true
        KissKHSettings.adultFilter = true
        Toast.makeText(context, "Impostazioni KissKH ripristinate", Toast.LENGTH_SHORT).show()
    }
}
