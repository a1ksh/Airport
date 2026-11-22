package kz.edu.airportcourse.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.AirportDb;

abstract class BaseActivity extends Activity {
    AirportDb db;
    AirportDb.Account user;
    private static final String SESSION = "airport_session";

    void page(int layout) {
        db = new AirportDb(this);
        setContentView(layout);
        for (int bannerId : new int[]{R.id.airport_banner, R.id.travel_hero}) {
            View banner = findViewById(bannerId);
            if (banner != null) banner.setClipToOutline(true);
        }
        View root = findViewById(R.id.page_root);
        if (android.os.Build.VERSION.SDK_INT >= 27 && android.os.Build.VERSION.SDK_INT < 30) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            var controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance, appearance);
            }
            int left = root.getPaddingLeft(), top = root.getPaddingTop();
            int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                var keyboard = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(left + bars.left, top + bars.top, right + bars.right,
                             bottom + Math.max(bars.bottom, keyboard.bottom));
                return insets;
            });
            root.requestApplyInsets();
        }
        click(R.id.back, this::finish);
    }

    long sessionId() { return getSharedPreferences(SESSION, MODE_PRIVATE).getLong("user", 0); }

    boolean signedIn(String role) {
        user = db.account(sessionId());
        if (user == null) enterLogin();
        else if (role == null || role.equals(user.role())) return true;
        else finish();
        return false;
    }

    void enterLogin() {
        getSharedPreferences(SESSION, MODE_PRIVATE).edit().remove("user").apply();
        startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    void enterHome(long id) {
        getSharedPreferences(SESSION, MODE_PRIVATE).edit().putLong("user", id).apply();
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    void open(Class<? extends Activity> activity) { startActivity(new Intent(this, activity)); }

    void external(String url, String packageName) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (packageName != null) {
            try { startActivity(new Intent(intent).setPackage(packageName)); return; }
            catch (ActivityNotFoundException ignored) { /* Use the website if Maps is absent. */ }
        }
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) { toast("Сілтемені ашатын қолданба табылмады."); }
    }

    void click(int id, Runnable action) {
        View control = findViewById(id);
        if (control != null) control.setOnClickListener(v -> action.run());
    }

    void text(int id, String value) { ((TextView) findViewById(id)).setText(value); }
    void visible(int id, boolean show) { findViewById(id).setVisibility(show ? View.VISIBLE : View.GONE); }
    void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    String value(int id) { return ((EditText) findViewById(id)).getText().toString().trim(); }

    void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    void spinner(Spinner spinner, String[] options) {
        var adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    void cards(int listId, CardAdapter adapter) { ((ListView) findViewById(listId)).setAdapter(adapter); }

    @Override protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }
}
