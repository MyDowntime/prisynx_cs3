package prisynx.cfcookie;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Prende il cf_clearance da una WebView su kisskh.co e lo manda al relay
 * (POST /renew) con lo User-Agent esatto della WebView.
 *
 * Perche serve: cf_clearance e HttpOnly e legato a IP + User-Agent; copiarlo a
 * mano e facile da sbagliare, da telefono impossibile senza estensioni. Qui
 * cookie e UA vengono letti dallo stesso browser che ha superato la verifica.
 */
public class MainActivity extends Activity {

    private static final String SITE = "https://kisskh.co/";
    private static final String PREFS = "cfcookie";
    // Su kisskh.co il cookie dura ~1 anno: diciamo al relay la scadenza vera,
    // cosi non lo considera "vecchio" dopo poche settimane. Il relay verifica
    // comunque ogni richiesta, quindi un TTL lungo non e un rischio.
    private static final long TTL_SECONDS = 365L * 24 * 3600;

    private static final int COLOR_BG = 0xFF0F1318;
    private static final int COLOR_FG = 0xFFE6E8EB;
    private static final int COLOR_MUTED = 0xFF9AA4AF;
    private static final int COLOR_OK = 0xFF22C55E;
    private static final int COLOR_ERR = 0xFFEF4444;
    private static final int COLOR_ACCENT = 0xFF2563EB;

    private WebView web;
    private EditText relayField;
    private EditText tokenField;
    private Button sendButton;
    private TextView status;
    private LinearLayout configBox;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastSent = "";
    private boolean sending = false;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!sending) {
                String cookies = cfCookies();
                boolean found = cookies.contains("cf_clearance=");
                sendButton.setEnabled(found);
                sendButton.setAlpha(found ? 1f : 0.4f);
                // Confronta solo cf_clearance: __cf_bm ruota ogni ~30 minuti e non
                // deve provocare un invio (e una verifica) ogni volta.
                String value = cfClearanceValue();
                if (found && !value.isEmpty() && !value.equals(lastSent)) {
                    lastSent = value;
                    send(cookies);
                }
            }
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // --- header -------------------------------------------------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(8));

        TextView title = new TextView(this);
        title.setText("CF Cookie");
        title.setTextColor(COLOR_FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.addView(title);

        TextView hint = new TextView(this);
        hint.setText("1. supera la verifica su kisskh.co  2. il cookie parte da solo verso il relay");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(2), 0, dp(8));
        header.addView(hint);

        Button settingsButton = new Button(this);
        settingsButton.setText("Impostazioni relay");
        settingsButton.setAllCaps(false);
        settingsButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.addView(settingsButton);

        configBox = new LinearLayout(this);
        configBox.setOrientation(LinearLayout.VERTICAL);
        configBox.setPadding(0, dp(8), 0, 0);
        configBox.setVisibility(View.GONE);

        relayField = new EditText(this);
        relayField.setHint("http://192.168.1.50:8191");
        relayField.setTextColor(COLOR_FG);
        relayField.setHintTextColor(COLOR_MUTED);
        relayField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        relayField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        configBox.addView(relayField);

        tokenField = new EditText(this);
        tokenField.setHint("RELAY_TOKEN");
        tokenField.setTextColor(COLOR_FG);
        tokenField.setHintTextColor(COLOR_MUTED);
        tokenField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        configBox.addView(tokenField);

        Button save = new Button(this);
        save.setText("Salva e ricarica");
        save.setAllCaps(false);
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        save.setOnClickListener(v -> {
            saveConfig();
            hideKeyboard();
            configBox.setVisibility(View.GONE);
            loadSite();
        });
        configBox.addView(save);
        header.addView(configBox);
        root.addView(header);

        settingsButton.setOnClickListener(v -> {
            configBox.setVisibility(configBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        // --- webview ------------------------------------------------------
        web = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        web.setLayoutParams(webParams);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // La WebView si presenta come "wv": Cloudflare la tratta peggio e lo UA
        // verrebbe comunque mandato al relay, quindi lo ripuliamo e usiamo quello.
        String ua = settings.getUserAgentString();
        if (ua != null) {
            ua = ua.replace("; wv", "").replace("Version/4.0 ", "");
            settings.setUserAgentString(ua);
        }
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web);

        // --- footer -------------------------------------------------------
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(14), dp(8), dp(14), dp(12));

        status = new TextView(this);
        status.setTextColor(COLOR_MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setText("Apro kisskh.co... se compare la casella, cliccala.");
        footer.addView(status);

        sendButton = new Button(this);
        sendButton.setText("Invia al relay");
        sendButton.setAllCaps(false);
        sendButton.setTextColor(Color.WHITE);
        sendButton.setBackgroundColor(COLOR_ACCENT);
        sendButton.setEnabled(false);
        sendButton.setAlpha(0.4f);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(8);
        sendButton.setLayoutParams(buttonParams);
        sendButton.setOnClickListener(v -> {
            String current = cfCookies();
            lastSent = cfClearanceValue();
            send(current);
        });
        footer.addView(sendButton);
        root.addView(footer);

        setContentView(root);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        relayField.setText(prefs.getString("relay", ""));
        tokenField.setText(prefs.getString("token", ""));
        if (prefs.getString("relay", "").isEmpty()) {
            configBox.setVisibility(View.VISIBLE);
            status.setText("Imposta URL del relay e token, poi salva.");
        } else {
            loadSite();
        }

        handler.postDelayed(poll, 1500);
    }

    private void loadSite() {
        if (relayField.getText().toString().trim().isEmpty()) {
            setStatus("Manca l'URL del relay (es. http://192.168.1.50:8191).", COLOR_ERR);
            configBox.setVisibility(View.VISIBLE);
            return;
        }
        saveConfig();
        setStatus("Carico kisskh.co... se compare la casella, cliccala.", COLOR_MUTED);
        web.loadUrl(SITE);
    }

    private void saveConfig() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("relay", relayField.getText().toString().trim())
                .putString("token", tokenField.getText().toString().trim())
                .apply();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) {
            return;
        }
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        focus.clearFocus();
    }

    private String cfCookies() {
        String raw = CookieManager.getInstance().getCookie("https://kisskh.co");
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> keep = new ArrayList<>();
        for (String part : raw.split(";")) {
            String pair = part.trim();
            if (pair.startsWith("cf_clearance=") || pair.startsWith("__cf_bm=")) {
                keep.add(pair);
            }
        }
        StringBuilder header = new StringBuilder();
        for (String pair : keep) {
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(pair);
        }
        return header.toString();
    }

    private String cfClearanceValue() {
        String header = cfCookies();
        for (String part : header.split(";")) {
            String pair = part.trim();
            if (pair.startsWith("cf_clearance=")) {
                return pair.substring("cf_clearance=".length());
            }
        }
        return "";
    }

    private void send(String cookieHeader) {
        if (sending) {
            return;
        }
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            setStatus("Nessun cf_clearance: supera prima la verifica.", COLOR_ERR);
            return;
        }
        String relay = relayField.getText().toString().trim();
        if (!relay.startsWith("http://") && !relay.startsWith("https://")) {
            relay = "http://" + relay;
        }
        while (relay.endsWith("/")) {
            relay = relay.substring(0, relay.length() - 1);
        }
        final String base = relay;
        final String token = tokenField.getText().toString().trim();
        final String userAgent = web.getSettings().getUserAgentString();

        sending = true;
        sendButton.setEnabled(false);
        setStatus("Invio al relay...", COLOR_MUTED);

        new Thread(() -> {
            boolean ok = false;
            String message;
            try {
                String endpoint = base + "/renew?token=" + URLEncoder.encode(token, "UTF-8");
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(45000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JSONObject body = new JSONObject();
                body.put("cookies", cookieHeader);
                body.put("user_agent", userAgent);
                body.put("ttl", TTL_SECONDS);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }

                int code = connection.getResponseCode();
                InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = readAll(in);
                connection.disconnect();

                JSONObject answer = new JSONObject(text);
                if (answer.optInt("status", 0) == 200) {
                    ok = true;
                    message = answer.optString("verdict", "cookie accettato");
                } else if (answer.has("error") && answer.optString("status", "").isEmpty()) {
                    message = answer.optString("error");
                } else {
                    message = answer.optString("verdict", "reason: " + answer.optString("reason", "?"));
                }
                if (code == 401) {
                    message = "token errato";
                }
            } catch (Exception exc) {
                message = exc.getClass().getSimpleName() + ": " + exc.getMessage();
            }
            final boolean success = ok;
            final String finalMessage = message;
            handler.post(() -> {
                sending = false;
                sendButton.setEnabled(!cfCookies().isEmpty());
                sendButton.setAlpha(sendButton.isEnabled() ? 1f : 0.4f);
                setStatus((success ? "OK: " : "ATTENZIONE: ") + finalMessage,
                        success ? COLOR_OK : COLOR_ERR);
            });
        }).start();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, count);
        }
        return buffer.toString("UTF-8");
    }

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
