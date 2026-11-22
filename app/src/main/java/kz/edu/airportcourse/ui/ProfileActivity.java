package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;

import java.util.Locale;

import kz.edu.airportcourse.R;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class ProfileActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_profile);
        click(R.id.edit_name, this::editName);
        click(R.id.change_password, this::editPassword);
        click(R.id.logout, this::enterLogin);
        click(R.id.media, () -> open(MediaActivity.class));
        click(R.id.reviews, () -> open(ReviewsActivity.class));
        click(R.id.passport, () -> open(PassportActivity.class));
    }

    @Override protected void onResume() {
        super.onResume();
        if (signedIn(null)) render();
    }

    private void render() {
        text(R.id.profile_name, user.name());
        text(R.id.profile_role, user.roleName());
        text(R.id.profile_email, user.email());
        StringBuilder initials = new StringBuilder();
        for (String word : user.name().trim().split("\\s+")) {
            if (!word.isEmpty() && initials.codePointCount(0, initials.length()) < 2) {
                initials.appendCodePoint(word.codePointAt(0));
            }
        }
        text(R.id.profile_avatar, initials.toString().toUpperCase(Locale.ROOT));
        View password = findViewById(R.id.change_password);
        password.setEnabled(!user.demo());
        password.setAlpha(user.demo() ? .55f : 1);
        visible(R.id.demo_help, user.demo());

        boolean passenger = user.role().equals(PASSENGER);
        visible(R.id.reviews, passenger || user.role().equals(ADMIN));
        visible(R.id.preferences, passenger);
        visible(R.id.passport, passenger);
        if (passenger) {
            var cities = db.passportCities(user.id());
            int completed = cities.stream().mapToInt(PassportCity::trips).sum();
            text(R.id.passport, getString(R.string.passport_profile, completed, cities.size()));
            text(R.id.profile_trips, "Сапарлар · " + db.bookings(user.id()).size());
            text(R.id.profile_saved, "Сақталған · " + db.savedFlights(user.id()).size());
            click(R.id.profile_trips, () -> open(BookingsActivity.class));
            click(R.id.profile_saved, () -> startActivity(new Intent(this, FlightsActivity.class)
                .putExtra(FlightsActivity.EXTRA_SAVED, true)));
            Switch alerts = findViewById(R.id.flight_alerts);
            alerts.setOnCheckedChangeListener(null);
            alerts.setChecked(db.flightAlerts(user.id()));
            alerts.setOnCheckedChangeListener((button, checked) -> db.setFlightAlerts(user.id(), checked));
            return;
        }
        boolean admin = user.role().equals(ADMIN);
        text(R.id.profile_trips, admin ? "Аккаунттар · " + db.accounts(user.id()).size() : "Рейс кестесі");
        text(R.id.profile_saved, "Өтінімдер · " + db.tickets(user.id()).size());
        click(R.id.profile_trips, () -> open(admin ? AdminActivity.class : FlightsActivity.class));
        click(R.id.profile_saved, () -> open(TicketsActivity.class));
    }

    private void editName() {
        View form = getLayoutInflater().inflate(R.layout.dialog_profile_name, null);
        EditText name = form.findViewById(R.id.edit_display_name);
        name.setText(user.name());
        name.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.profile_edit)
            .setView(form).setNegativeButton("Кері", null).setPositiveButton("Сақтау", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!db.rename(user.id(), name.getText().toString())) {
                name.setError("Атыңызды енгізіңіз.");
                return;
            }
            dialog.dismiss();
            if (signedIn(null)) render();
        }));
        dialog.show();
    }

    private void editPassword() {
        if (user.demo()) return;
        View form = getLayoutInflater().inflate(R.layout.dialog_profile_password, null);
        EditText current = form.findViewById(R.id.current_password);
        EditText replacement = form.findViewById(R.id.new_password);
        EditText repeat = form.findViewById(R.id.repeat_password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.profile_password)
            .setView(form).setNegativeButton("Кері", null).setPositiveButton("Сақтау", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String proposed = replacement.getText().toString();
            if (proposed.length() < 8) { replacement.setError("Кемінде 8 таңба енгізіңіз."); return; }
            if (!proposed.equals(repeat.getText().toString())) { repeat.setError("Құпиясөздер сәйкес емес."); return; }
            if (!db.changePassword(user.id(), current.getText().toString(), proposed)) {
                current.setError("Қазіргі құпиясөз қате.");
                return;
            }
            dialog.dismiss();
            toast("Құпиясөз ауыстырылды.");
        }));
        dialog.show();
    }
}
