package kz.edu.airportcourse.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import kz.edu.airportcourse.R;

/** The launcher uses the same accounts, sessions and role-specific screens as the website. */
public final class SharedAirportActivity extends ComponentActivity {
    private static final String EMULATOR_SERVER = "http://10.0.2.2:8765";
    private WebView web;
    private TicketSaver ticketSaver;
    private String origin;
    private boolean failed;
    private Runnable retryLocalConnection;
    private final ActivityResultLauncher<String> localNetwork = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            Runnable retry = retryLocalConnection;
            retryLocalConnection = null;
            if (granted && retry != null) retry.run();
            else if (!granted) Toast.makeText(this, R.string.shared_local_denied, Toast.LENGTH_LONG).show();
        });

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ticketSaver = new TicketSaver(this, state);
        setContentView(R.layout.activity_shared_airport);
        origin = canonicalOrigin(getPreferences(MODE_PRIVATE).getString("origin", null), true);
        if (origin == null) origin = canonicalOrigin(getString(R.string.shared_server_url), true);
        ((TextView) findViewById(R.id.shared_host)).setText(origin == null
                ? getString(R.string.app_name) : URI.create(origin).getAuthority());
        findViewById(R.id.shared_settings).setOnClickListener(v -> chooseServer());
        findViewById(R.id.open_about).setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        findViewById(R.id.shared_retry).setOnClickListener(v -> {
            if (origin == null) chooseServer();
            else web.loadUrl(origin + "/");
        });

        web = findViewById(R.id.shared_web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new ServerClient());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!failed && web.canGoBack()) web.goBack();
                else finish();
            }
        });
        String previous = state == null ? null : state.getString("page");
        if (origin == null) {
            ((TextView) findViewById(R.id.shared_status_title)).setText(R.string.shared_server_title);
            ((TextView) findViewById(R.id.shared_status_detail)).setText(R.string.shared_server_help);
            ((TextView) findViewById(R.id.shared_retry)).setText(R.string.shared_server_connect);
            showConnectionError();
            return;
        }
        web.loadUrl(origin.equals(canonicalOrigin(previous, false)) ? previous : origin + "/");
    }

    private String canonicalOrigin(String input, boolean rootOnly) {
        if (input == null) return null;
        try {
            URI uri = URI.create(input);
            String host = uri.getHost();
            int port = uri.getPort();
            boolean emulatorServer = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    && "http".equalsIgnoreCase(uri.getScheme()) && "10.0.2.2".equals(host) && port == 8765;
            if ((!emulatorServer && !"https".equalsIgnoreCase(uri.getScheme())) || host == null || uri.getRawUserInfo() != null
                    || port == 0 || port > 65535 || uri.getRawAuthority().endsWith(":")) return null;
            if (rootOnly && (uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))) return null;
            if (emulatorServer) return EMULATOR_SERVER;
            return "https://" + host.toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port);
        } catch (IllegalArgumentException badUrl) {
            return null;
        }
    }

    private void chooseServer() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setText(origin);
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.shared_server_title).setMessage(R.string.shared_server_help)
            .setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.shared_server_connect, null).create();
        dialog.show();
        View connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        connect.setOnClickListener(v -> {
            String candidate = canonicalOrigin(input.getText().toString().trim(), true);
            if (candidate == null) {
                input.setError(getString(R.string.shared_server_invalid));
                return;
            }
            if (candidate.equals(EMULATOR_SERVER) && Build.VERSION.SDK_INT >= 37
                    && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                retryLocalConnection = () -> { if (dialog.isShowing()) connect.performClick(); };
                localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
                return;
            }
            connect.setEnabled(false);
            input.setEnabled(false);
            new Thread(() -> {
                boolean ready = checkAirport(candidate);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || !dialog.isShowing()) return;
                    if (!ready) {
                        connect.setEnabled(true);
                        input.setEnabled(true);
                        input.setError(getString(R.string.shared_server_unavailable));
                        return;
                    }
                    getPreferences(MODE_PRIVATE).edit().putString("origin", candidate).apply();
                    dialog.dismiss();
                    // A new WebView also starts a new navigation history for the selected server.
                    recreate();
                });
            }, "airport-server-check").start();
        });
    }

    /** Probe only the public health endpoint, with no session or credentials. */
    private static boolean checkAirport(String candidate) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(candidate + "/health").toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) return false;
            try (InputStream stream = connection.getInputStream()) {
                byte[] bytes = new byte[1024];
                int size = 0;
                while (size < bytes.length) {
                    int count = stream.read(bytes, size, bytes.length - size);
                    if (count < 0) break;
                    size += count;
                }
                if (size == bytes.length) return false;
                JSONObject health = new JSONObject(new String(bytes, 0, size, StandardCharsets.UTF_8));
                return "Airport".equals(health.optString("app")) && "ready".equals(health.optString("status"));
            }
        } catch (java.io.IOException | org.json.JSONException failedCheck) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private final class ServerClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String target = canonicalOrigin(request.getUrl().toString(), false);
            if (origin != null && origin.equals(target)) {
                if (request.isForMainFrame() && request.hasGesture() && "GET".equals(request.getMethod())
                        && ticketSaver.open(request.getUrl())) return true;
                return false;
            }
            if (target != null && request.isForMainFrame() && request.hasGesture()) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (ActivityNotFoundException missing) {
                    Toast.makeText(SharedAirportActivity.this, R.string.shared_server_external_missing, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (origin != null && origin.equals(canonicalOrigin(request.getUrl().toString(), false))) return null;
            return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                java.util.Collections.emptyMap(), null);
        }

        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            failed = false;
            findViewById(R.id.shared_error).setVisibility(View.GONE);
            findViewById(R.id.shared_progress).setVisibility(View.VISIBLE);
            web.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            findViewById(R.id.shared_progress).setVisibility(View.GONE);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showConnectionError();
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame()) showConnectionError();
        }
    }

    private void showConnectionError() {
        failed = true;
        web.setVisibility(View.INVISIBLE);
        findViewById(R.id.shared_progress).setVisibility(View.GONE);
        findViewById(R.id.shared_error).setVisibility(View.VISIBLE);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        ticketSaver.saveState(state);
        state.putString("page", web.getUrl());
    }

    @Override protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override protected void onDestroy() {
        ((android.view.ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
