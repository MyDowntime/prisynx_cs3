package prisynx.hub

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.runBlocking

/**
 * StreamCenter-like settings screen, built in code (no resources needed):
 * collapsible sections for sources (on/off + order), TMDB rows and TopX rows.
 */
@Suppress("SetTextI18n", "UseSwitchCompatOrMaterialCode")
object HubSettingsDialog {
    private const val BG = 0xFF15171C.toInt()
    private const val CARD = 0xFF1F232B.toInt()
    private const val ACCENT = 0xFF3D8BFF.toInt()
    private const val TEXT = 0xFFECEFF4.toInt()
    private const val SUB = 0xFF9AA3B2.toInt()

    fun show(context: Context) {
        HubSettings.init(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
        }
        root.addView(title(context, "PriSynx", 22f))
        root.addView(note(context, "Le modifiche vengono salvate subito. Ricarica la home per vedere i cataloghi aggiornati."))

        root.addView(section(context, "Provider", "Accendi/spegni e ordina le sorgenti: l'ordine è la priorità nella lista stream.", true) {
            sourcesSection(context, it)
        })
        root.addView(section(context, "Cataloghi TMDB", "Liste della home basate su TMDB. La ricerca usa sempre TMDB.", false) {
            tmdbSection(context, it)
        })
        root.addView(section(context, "TopX", "Classifiche Top 10 dei servizi di streaming (addon Stremio TopX).", false) {
            topxSection(context, it)
        })

        val reset = button(context, "Ripristina impostazioni predefinite") {
            HubSettings.resetAll()
            TopX.clearCache()
            toast(context, "Impostazioni ripristinate: riapri il menu")
        }
        root.addView(reset, matchWrap().apply { topMargin = dp(context, 16) })

        val scroll = ScrollView(context).apply {
            setBackgroundColor(BG)
            addView(root)
        }
        AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton("Chiudi", null)
            .show()
            .window?.setBackgroundDrawable(GradientDrawable().apply { setColor(BG); cornerRadius = dp(context, 12).toFloat() })
    }

    // ------------------------------------------------------------ sections

    private fun sourcesSection(context: Context, body: LinearLayout) {
        body.removeAllViews()
        val order = HubSettings.orderedSources().toMutableList()
        order.forEachIndexed { index, source ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 2), 0, dp(context, 2))
            }
            val position = TextView(context).apply {
                text = "${index + 1}."
                setTextColor(SUB)
                minWidth = dp(context, 28)
            }
            val sw = switch(context, source.label, HubSettings.isSourceEnabled(source.id)) { _, on ->
                HubSettings.setSourceEnabled(source.id, on)
            }
            val up = arrow(context, "▲", index > 0) {
                order.add(index - 1, order.removeAt(index))
                HubSettings.saveOrder(order)
                sourcesSection(context, body)
            }
            val down = arrow(context, "▼", index < order.lastIndex) {
                order.add(index + 1, order.removeAt(index))
                HubSettings.saveOrder(order)
                sourcesSection(context, body)
            }
            row.addView(position)
            row.addView(sw, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(up)
            row.addView(down)
            body.addView(row)
        }
        val bulk = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bulk.addView(button(context, "Tutti") {
            HubSettings.sources.forEach { HubSettings.setSourceEnabled(it.id, true) }
            sourcesSection(context, body)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bulk.addView(button(context, "Nessuno") {
            HubSettings.sources.forEach { HubSettings.setSourceEnabled(it.id, false) }
            sourcesSection(context, body)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(bulk)

        val torrents = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 2))
        }
        val count = TextView(context).apply {
            text = "IlCorsaroViola: max ${HubSettings.torrentMax} risultati"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val minus = arrow(context, "\u2212", HubSettings.torrentMax > 1) {
            HubSettings.torrentMax = HubSettings.torrentMax - 1
            sourcesSection(context, body)
        }
        val plus = arrow(context, "+", HubSettings.torrentMax < 20) {
            HubSettings.torrentMax = HubSettings.torrentMax + 1
            sourcesSection(context, body)
        }
        torrents.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        torrents.addView(minus)
        torrents.addView(plus)
        body.addView(torrents)
        body.addView(note(context, "Prima il 4K e il Full HD con più seed (se presenti), poi i più seedati."))
    }

    private fun tmdbSection(context: Context, body: LinearLayout) {
        HubSettings.tmdbRows.forEach { row ->
            body.addView(switch(context, row.row.name, HubSettings.isTmdbRowEnabled(row)) { _, on ->
                HubSettings.setTmdbRowEnabled(row, on)
            })
        }
    }

    private fun topxSection(context: Context, body: LinearLayout) {
        body.addView(switch(context, "Mostra cataloghi TopX", HubSettings.topxEnabled) { _, on ->
            HubSettings.topxEnabled = on
        })
        body.addView(switch(context, "TopX prima dei cataloghi TMDB", HubSettings.topxFirst) { _, on ->
            HubSettings.topxFirst = on
        })
        body.addView(note(context, "URL manifest TopX (configurabile su topx.stremio-italia.eu)"))
        val input = EditText(context).apply {
            setText(HubSettings.topxUrl)
            setTextColor(TEXT)
            setHintTextColor(SUB)
            hint = "https://…/manifest.json"
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        body.addView(input, matchWrap())
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button(context, "Salva URL e ricarica") {
            HubSettings.topxUrl = input.text.toString().ifBlank { HubSettings.DEFAULT_TOPX_URL }
            TopX.clearCache()
            loadTopxCatalogs(context, list, force = true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button(context, "URL predefinito") {
            input.setText(HubSettings.DEFAULT_TOPX_URL)
            HubSettings.topxUrl = HubSettings.DEFAULT_TOPX_URL
            TopX.clearCache()
            loadTopxCatalogs(context, list, force = true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(actions)
        body.addView(list)
        loadTopxCatalogs(context, list, force = false)
    }

    private fun loadTopxCatalogs(context: Context, list: LinearLayout, force: Boolean) {
        list.removeAllViews()
        list.addView(note(context, "Caricamento cataloghi…"))
        val main = Handler(Looper.getMainLooper())
        Thread {
            val catalogs = try {
                runBlocking { TopX.catalogs(HubSettings.topxUrl, force) }
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                list.removeAllViews()
                if (catalogs.isEmpty()) {
                    list.addView(note(context, "Nessun catalogo trovato: controlla l'URL o la connessione."))
                    return@post
                }
                val switches = mutableListOf<Pair<TopxCatalog, Switch>>()
                val bulk = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                bulk.addView(button(context, "Mostra tutti") {
                    switches.forEach { (c, s) -> HubSettings.setTopxCatalogEnabled(c.key, true); s.isChecked = true }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                bulk.addView(button(context, "Nascondi tutti") {
                    switches.forEach { (c, s) -> HubSettings.setTopxCatalogEnabled(c.key, false); s.isChecked = false }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                list.addView(bulk)
                catalogs.forEach { catalog ->
                    val sw = switch(context, catalog.label, HubSettings.isTopxCatalogEnabled(catalog.key)) { _, on ->
                        HubSettings.setTopxCatalogEnabled(catalog.key, on)
                    }
                    switches.add(catalog to sw)
                    list.addView(sw)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ widgets

    private fun section(
        context: Context,
        name: String,
        description: String,
        expanded: Boolean,
        fill: (LinearLayout) -> Unit
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(context, 10).toFloat() }
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        var filled = false
        if (expanded) {
            fill(body); filled = true
        }
        val header = TextView(context).apply {
            text = (if (expanded) "▾  " else "▸  ") + name
            setTextColor(ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isClickable = true
            isFocusable = true
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            if (open && !filled) {
                fill(body); filled = true
            }
            body.visibility = if (open) View.VISIBLE else View.GONE
            header.text = (if (open) "▾  " else "▸  ") + name
        }
        card.addView(header)
        card.addView(note(context, description))
        card.addView(body)
        return card.also {
            it.layoutParams = matchWrap().apply { topMargin = dp(context, 10) }
        }
    }

    private fun switch(
        context: Context,
        label: String,
        checked: Boolean,
        onChange: (CompoundButton, Boolean) -> Unit
    ) = Switch(context).apply {
        text = label
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = checked
        setPadding(0, dp(context, 6), 0, dp(context, 6))
        setOnCheckedChangeListener(onChange)
    }

    private fun arrow(context: Context, symbol: String, enabled: Boolean, onClick: () -> Unit) =
        TextView(context).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (enabled) ACCENT else 0x33FFFFFF)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            minWidth = dp(context, 40)
            minHeight = dp(context, 40)
            if (enabled) setOnClickListener { onClick() }
        }

    private fun button(context: Context, label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(0xFF2A3140.toInt()); cornerRadius = dp(context, 8).toFloat() }
        setOnClickListener { onClick() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 2)) }
    }

    private fun title(context: Context, value: String, size: Float) = TextView(context).apply {
        text = value
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun note(context: Context, value: String) = TextView(context).apply {
        text = value
        setTextColor(SUB)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(context, 2), 0, dp(context, 4))
    }

    private fun toast(context: Context, value: String) =
        Toast.makeText(context, value, Toast.LENGTH_SHORT).show()

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
