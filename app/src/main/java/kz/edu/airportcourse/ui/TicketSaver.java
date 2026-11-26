package kz.edu.airportcourse.ui;

import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kz.edu.airportcourse.R;

/** Download an authenticated ticket before opening Android's document picker. */
final class TicketSaver {
    private static final Pattern PATH = Pattern.compile("/api/trips/(NQZ-[A-F0-9]{8})/ticket\\.pdf");
    private static final String PENDING_FILE = "pending_ticket_pdf";
    private final ComponentActivity activity;
    private final ActivityResultLauncher<String> picker;
    private File pending;
    private boolean loading;

    TicketSaver(ComponentActivity activity, Bundle state) {
        this.activity = activity;
        String name = state == null ? null : state.getString(PENDING_FILE);
        if (name != null) pending = new File(activity.getCacheDir(), name);
        picker = activity.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), this::store);
    }

    // The WebView has already checked the origin and the user's navigation gesture.
    boolean open(Uri uri) {
        Matcher match = PATH.matcher(uri.getEncodedPath() == null ? "" : uri.getEncodedPath());
        if (!match.matches() || uri.getEncodedQuery() != null) return false;
        if (pending != null || loading) return true;
        loading = true;
        String filename = "Airport-" + match.group(1) + ".pdf";
        String address = uri.toString();
        String cookie = CookieManager.getInstance().getCookie(address);
        new Thread(() -> {
            File ticket = fetch(address, cookie);
            activity.runOnUiThread(() -> {
                loading = false;
                if (activity.isFinishing() || activity.isDestroyed()) {
                    if (ticket != null) ticket.delete();
                    return;
                }
                if (ticket == null) {
                    message(R.string.ticket_save_failed);
                    return;
                }
                pending = ticket;
                try {
                    picker.launch(filename);
                } catch (ActivityNotFoundException missing) {
                    store(null);
                    message(R.string.ticket_save_failed);
                }
            });
        }, "airport-fetch-ticket").start();
        return true;
    }

    private File fetch(String address, String cookie) {
        File file = null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || !"application/pdf".equals(connection.getContentType())) return null;
            file = File.createTempFile("airport-ticket-", ".pdf", activity.getCacheDir());
            try (InputStream input = connection.getInputStream()) {
                Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return file;
        } catch (IOException failed) {
            if (file != null) file.delete();
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    void saveState(Bundle state) {
        if (pending != null) state.putString(PENDING_FILE, pending.getName());
    }

    private void store(Uri destination) {
        File ticket = pending;
        pending = null;
        if (ticket == null) return;
        if (destination == null) {
            ticket.delete();
            return;
        }
        new Thread(() -> {
            try (OutputStream output = activity.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Document unavailable");
                Files.copy(ticket.toPath(), output);
            } catch (IOException | SecurityException failed) {
                activity.runOnUiThread(() -> message(R.string.ticket_save_failed));
                return;
            } finally {
                ticket.delete();
            }
            activity.runOnUiThread(() -> message(R.string.ticket_saved));
        }, "airport-save-ticket").start();
    }

    private void message(int resource) {
        Toast.makeText(activity.getApplicationContext(), resource, Toast.LENGTH_LONG).show();
    }
}
