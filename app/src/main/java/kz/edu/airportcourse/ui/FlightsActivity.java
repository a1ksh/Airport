package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.AirportDb.Booking;
import kz.edu.airportcourse.data.AirportDb.Flight;
import kz.edu.airportcourse.data.WeatherData;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class FlightsActivity extends BaseActivity {
    static final String EXTRA_SAVED = "saved_flights";
    static final String EXTRA_TODAY = "today_flights";
    static final String EXTRA_DISRUPTED = "disrupted_flights";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private int direction;
    private LocalDate date;
    private boolean savedOnly;
    private boolean disrupted;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_flights);
        direction = state == null ? 0 : state.getInt("direction");
        savedOnly = state == null ? getIntent().getBooleanExtra(EXTRA_SAVED, false) : state.getBoolean(EXTRA_SAVED);
        disrupted = state == null ? getIntent().getBooleanExtra(EXTRA_DISRUPTED, false) : state.getBoolean(EXTRA_DISRUPTED);
        if (state == null && getIntent().getBooleanExtra(EXTRA_TODAY, false)) date = LocalDate.now(ASTANA);
        if (state != null && state.containsKey("date")) date = LocalDate.ofEpochDay(state.getLong("date"));
        click(R.id.departures, () -> { direction = 0; render(); });
        click(R.id.arrivals, () -> { direction = 1; render(); });
        click(R.id.select_date, this::chooseDate);
        click(R.id.clear_date, () -> { date = null; render(); });
        click(R.id.saved_filter, () -> { savedOnly = !savedOnly; render(); });
        click(R.id.add_flight, () -> edit(null));
        click(R.id.disruption_filter, () -> { disrupted = !disrupted; render(); });
        EditText search = findViewById(R.id.search);
        search.setOnEditorActionListener((view, action, event) -> {
            hideKeyboard();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (user != null) render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (!signedIn(null)) return;
        db.ensureDemoSchedule();
        visible(R.id.add_flight, user.role().equals(OPERATOR));
        visible(R.id.disruption_filter, user.role().equals(OPERATOR));
        visible(R.id.saved_filter, user.role().equals(PASSENGER));
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("direction", direction);
        state.putBoolean(EXTRA_SAVED, savedOnly);
        state.putBoolean(EXTRA_DISRUPTED, disrupted);
        if (date != null) state.putLong("date", date.toEpochDay());
        super.onSaveInstanceState(state);
    }

    private void chooseDate() {
        LocalDate selected = date == null ? LocalDate.now(ASTANA) : date;
        hideKeyboard();
        new DatePickerDialog(this, (picker, year, month, day) -> {
            date = LocalDate.of(year, month + 1, day);
            render();
        }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth()).show();
    }

    private void render() {
        if (user == null) return;
        boolean passenger = user.role().equals(PASSENGER);
        Set<Long> saved = passenger ? db.savedFlights(user.id()) : Collections.emptySet();
        long now = System.currentTimeMillis();
        var flights = db.flights(direction, value(R.id.search));
        if (disrupted && user.role().equals(OPERATOR)) flights.removeIf(f -> f.status() != 1 && f.status() != 3);
        if (date != null) flights.removeIf(f -> !Instant.ofEpochMilli(f.time()).atOffset(ASTANA).toLocalDate().equals(date));
        if (passenger && savedOnly) flights.removeIf(f -> !saved.contains(f.id()));
        if (date == null && !savedOnly) {
            if (passenger) flights.removeIf(f -> f.time() < now);
            else flights.sort((first, second) -> {
                boolean firstPast = first.time() < now, secondPast = second.time() < now;
                if (firstPast != secondPast) return Boolean.compare(firstPast, secondPast);
                return firstPast ? Long.compare(second.time(), first.time()) : Long.compare(first.time(), second.time());
            });
        }
        activeTab(R.id.departures, direction == 0);
        activeTab(R.id.arrivals, direction == 1);
        activeTab(R.id.saved_filter, savedOnly);
        activeTab(R.id.disruption_filter, disrupted);
        text(R.id.select_date, date == null ? getString(R.string.flight_date) : date.format(DAY));
        visible(R.id.clear_date, date != null);
        text(R.id.results, (passenger && savedOnly ? "Сақталған · " : "") + flights.size() + " рейс · Демо кесте");
        visible(R.id.empty, flights.isEmpty());
        ListView list = findViewById(R.id.flight_list);
        list.setAdapter(new FlightAdapter(this, flights, saved));
        list.setOnItemClickListener((parent, v, position, id) -> {
            hideKeyboard();
            Flight f = flights.get(position);
            if (user.role().equals(OPERATOR)) edit(f);
            else details(f, saved.contains(f.id()));
        });
    }

    private void activeTab(int id, boolean selected) {
        styleButton(findViewById(id), selected);
    }

    private void styleButton(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.primary : R.drawable.secondary);
        button.setTextColor(selected ? android.graphics.Color.WHITE : getColor(R.color.blue));
        button.setSelected(selected);
    }

    private void details(Flight f, boolean saved) {
        View content = getLayoutInflater().inflate(R.layout.dialog_flight_details, null);
        FlightAdapter.bindFlight(content, f);
        var dialog = new AlertDialog.Builder(this).setTitle(f.number()).setView(content).setNegativeButton("Жабу", null);
        if (user.role().equals(PASSENGER)) {
            dialog.setNeutralButton(saved ? "Сақтаудан алу" : "Сақтау", (d, b) -> {
                db.setSaved(user.id(), f.id(), !saved);
                toast(saved ? "Рейс сақталғандардан алынды." : "Рейс сақталды.");
                render();
            });
            Booking existing = db.booking(user.id(), f.id());
            if (existing != null) {
                dialog.setPositiveButton("Талон", (d, b) -> BookingsActivity.showPass(this, existing, this::render));
            } else if (f.direction() == 0 && f.status() != 3 && f.time() > System.currentTimeMillis()) {
                dialog.setPositiveButton(R.string.select_seat, (d, b) -> chooseSeat(f));
            }
        }
        AlertDialog details = dialog.show();
        Button weather = content.findViewById(R.id.flight_weather);
        weather.setText(getString(R.string.flight_weather_format, f.city(), getString(R.string.activity_weather_condition_text)));
        weather.setOnClickListener(view -> startActivity(new Intent(this, WeatherActivity.class)
            .putExtra(WeatherActivity.EXTRA_CITY, f.city())));
        WeatherData.load(this, f.city(), false, report -> {
            if (!isFinishing() && !isDestroyed() && details.isShowing()) {
                weather.setText(getString(R.string.flight_weather_format, f.city(),
                    report == null ? getString(R.string.short_weather) : WeatherData.summary(report)));
            }
        });
    }

    private void chooseSeat(Flight flight) {
        List<String> seats = seats();
        Set<String> occupied = db.occupiedSeats(user.id(), flight.id());
        View content = getLayoutInflater().inflate(R.layout.dialog_seats, null);
        LinearLayout rows = content.findViewById(R.id.seat_rows);
        TextView selection = content.findViewById(R.id.seat_selection);
        selection.setText(occupied.containsAll(seats) ? "Бұл рейсте бос орын жоқ." : "Бос орынды таңдаңыз.");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.select_seat) + " · " + flight.number())
            .setView(content).setNegativeButton("Кері", null).setPositiveButton("Броньдау", null).create();
        Button[] selected = {null};
        int aisleWidth = Math.round(12 * getResources().getDisplayMetrics().density);
        for (int index = 0; index < seats.size(); index += 6) {
            LinearLayout row = new LinearLayout(this);
            rows.addView(row);
            for (String seat : seats.subList(index, index + 6)) {
                if (seat.endsWith("D")) row.addView(new Space(this), new LinearLayout.LayoutParams(aisleWidth, 1));
                Button cell = (Button) getLayoutInflater().inflate(R.layout.seat_button, row, false);
                row.addView(cell);
                cell.setText(seat);
                cell.setEnabled(!occupied.contains(seat));
                if (!cell.isEnabled()) {
                    cell.setBackgroundResource(R.drawable.seat_occupied);
                    cell.setTextColor(getColor(R.color.muted));
                }
                cell.setContentDescription("Орын " + seat + (cell.isEnabled() ? "" : ", бос емес"));
                cell.setOnClickListener(v -> {
                    if (selected[0] != null) styleButton(selected[0], false);
                    selected[0] = cell;
                    styleButton(cell, true);
                    selection.setText(getString(R.string.selected_seat_format, seat));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                });
            }
        }
        dialog.setOnShowListener(d -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(false);
            confirm.setOnClickListener(v -> {
                try {
                    Booking booking = db.book(user.id(), flight.id(), selected[0].getText().toString());
                    dialog.dismiss();
                    BookingsActivity.showPass(this, booking, this::render);
                } catch (IllegalStateException e) {
                    dialog.dismiss();
                    new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("Жабу", null).show();
                    render();
                }
            });
        });
        dialog.show();
    }

    private void edit(Flight flight) {
        View form = getLayoutInflater().inflate(R.layout.dialog_flight, null);
        EditText number = form.findViewById(R.id.flight_code), city = form.findViewById(R.id.city), gate = form.findViewById(R.id.gate);
        Button clock = form.findViewById(R.id.time);
        Spinner flightDirection = form.findViewById(R.id.direction), status = form.findViewById(R.id.flight_status);
        spinner(flightDirection, new String[]{"Ұшып шығу", "Ұшып келу"});
        spinner(status, FLIGHT_STATES);
        if (flight != null) {
            number.setText(flight.number());
            city.setText(flight.city());
            gate.setText(flight.gate());
            flightDirection.setSelection(flight.direction());
            status.setSelection(flight.status());
        } else flightDirection.setSelection(direction);
        long[] selectedTime = {flight == null ? System.currentTimeMillis() + 3_600_000L : flight.time()};
        clock.setText(time(selectedTime[0]));
        clock.setOnClickListener(v -> {
            var local = LocalDateTime.ofInstant(Instant.ofEpochMilli(selectedTime[0]), ASTANA);
            new DatePickerDialog(this, (picker, year, month, day) ->
                new TimePickerDialog(this, (tp, hour, minute) -> {
                    selectedTime[0] = LocalDate.of(year, month + 1, day).atTime(hour, minute).toInstant(ASTANA).toEpochMilli();
                    clock.setText(time(selectedTime[0]));
                }, local.getHour(), local.getMinute(), true).show(),
                local.getYear(), local.getMonthValue() - 1, local.getDayOfMonth()).show();
        });
        var editor = new AlertDialog.Builder(this).setTitle(flight == null ? "Рейс қосу" : "Рейсті өңдеу")
            .setView(form).setNegativeButton("Артқа", null).setPositiveButton("Сақтау", null);
        if (flight != null && flight.direction() == 0) {
            editor.setNeutralButton("Жолаушылар", (d, b) -> showManifest(flight));
        }
        AlertDialog dialog = editor.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String flightNumber = number.getText().toString().trim().toUpperCase(Locale.ROOT);
            String destination = city.getText().toString().trim();
            String flightGate = gate.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (flightNumber.isEmpty()) { number.setError("Рейс нөмірін жазыңыз."); return; }
            if (destination.isEmpty()) { city.setError("Қаланы жазыңыз."); return; }
            if (flightGate.isEmpty()) { gate.setError("Қақпаны жазыңыз."); return; }
            Flight edited = new Flight(flight == null ? 0 : flight.id(), flightNumber, destination, flightGate,
                selectedTime[0], flightDirection.getSelectedItemPosition(), status.getSelectedItemPosition());
            if (!db.saveFlight(user.id(), edited)) { number.setError("Рейс нөмірі қайталанды."); return; }
            direction = edited.direction();
            dialog.dismiss();
            render();
            toast("Рейс сақталды.");
        }));
        dialog.show();
    }

    private void showManifest(Flight flight) {
        var passengers = db.manifest(user.id(), flight.id());
        String[] rows = new String[passengers.size()];
        int checked = 0;
        for (int i = 0; i < rows.length; i++) {
            var entry = passengers.get(i);
            if (entry.checked()) checked++;
            rows[i] = entry.name() + (entry.seat() == null ? "" : "  ·  " + entry.seat()) + "\n"
                + entry.code() + "  ·  " + (entry.completed() ? getString(R.string.passport_trip_done) : entry.checked() ? "Тіркелді" : "Бронь");
        }
        var dialog = new AlertDialog.Builder(this).setTitle(flight.number() + " · Тіркелді " + checked + "/" + rows.length)
            .setNegativeButton("Жабу", null);
        if (rows.length == 0) dialog.setMessage("Бұл рейске бронь жасалмаған.");
        else dialog.setItems(rows, (d, position) -> {
            var entry = passengers.get(position);
            if (entry.completed()) {
                toast(getString(R.string.passport_trip_done));
                showManifest(flight);
                return;
            }
            if (entry.checked()) {
                boolean eligible = flight.direction() == 0 && flight.status() != 3 && flight.time() <= System.currentTimeMillis();
                var completion = new AlertDialog.Builder(this).setTitle(R.string.passport_finish)
                    .setMessage(getString(eligible ? R.string.passport_confirm : R.string.passport_manifest_hint, entry.name(), entry.code()))
                    .setNegativeButton("Кері", (back, b) -> showManifest(flight));
                if (eligible) completion.setPositiveButton(R.string.passport_finish, (confirm, b) -> {
                    toast(getString(db.completeTrip(user.id(), entry.code()) ? R.string.passport_finish_success : R.string.passport_finish_unavailable));
                    showManifest(flight);
                });
                completion.show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Жолаушыны тіркеу")
                .setMessage(entry.name() + " · " + entry.code())
                .setNegativeButton("Кері", (back, b) -> showManifest(flight))
                .setPositiveButton("Тіркеу", (confirm, b) -> {
                    toast(db.checkIn(user.id(), entry.code()) ? "Жолаушы тіркелді." : "Бұл броньды қазір тіркеу мүмкін емес.");
                    showManifest(flight);
                }).show();
        });
        dialog.show();
    }
}
