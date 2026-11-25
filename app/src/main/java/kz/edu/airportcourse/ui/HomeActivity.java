package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.WeatherData;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class HomeActivity extends BaseActivity {
    private static final String MAPS = "https://www.google.com/maps/dir/?api=1&destination=51.0222%2C71.4669";
    private int weatherRequest;
    private boolean bannerAnimated;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_home);
        click(R.id.flights, () -> open(FlightsActivity.class));
        click(R.id.nav_flights, () -> open(FlightsActivity.class));
        click(R.id.bookings, () -> open(BookingsActivity.class));
        click(R.id.profile, () -> open(ProfileActivity.class));
        click(R.id.notifications, () -> open(NoticesActivity.class));
        click(R.id.saved, () -> startActivity(new Intent(this, FlightsActivity.class)
            .putExtra(FlightsActivity.EXTRA_SAVED, true)));
        click(R.id.inbox, () -> open(TicketsActivity.class));
        click(R.id.accounts, () -> open(AdminActivity.class));
        click(R.id.checkin, this::checkIn);
        click(R.id.staff_log, () -> open(StaffListActivity.class));
        click(R.id.weather, () -> openWeather(WeatherData.ASTANA_CITY));
        click(R.id.maps, () -> external(MAPS, "com.google.android.apps.maps"));
        click(R.id.official, () -> external("https://www.nn-airport.kz/flight-status", null));
        click(R.id.baggage, this::guide);
        click(R.id.media, () -> open(MediaActivity.class));
        click(R.id.reviews, () -> open(ReviewsActivity.class));
        click(R.id.open_about, () -> open(AboutActivity.class));
        click(R.id.more, this::menu);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!signedIn(null)) return;
        boolean passenger = user.role().equals(PASSENGER);
        boolean operator = user.role().equals(OPERATOR);
        text(R.id.welcome, "Сәлем, " + user.name() + "!");
        text(R.id.home_action, "Қайда ұшамыз?");
        text(R.id.flights, "Рейс іздеу  →");
        visible(R.id.bookings, passenger);
        visible(R.id.per_trip, passenger);
        visible(R.id.airport_banner, passenger);
        visible(R.id.staff_panel, !passenger);
        visible(R.id.checkin, operator);
        visible(R.id.accounts, user.role().equals(ADMIN));
        visible(R.id.staff_incidents, operator);
        visible(R.id.reviews, !operator);
        if (!passenger) showStaff(operator);
        if (passenger && !bannerAnimated) {
            bannerAnimated = true;
            findViewById(R.id.airport_banner).startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.banner_enter));
        }
        int unread = db.unread(user.id());
        text(R.id.inbox, unread == 0 ? "Хабарлар" : "Хабарлар · " + unread);
        int notices = db.unreadNotices(user.id());
        visible(R.id.notification_count, notices > 0);
        text(R.id.notification_count, notices > 99 ? "99+" : Integer.toString(notices));
        findViewById(R.id.notifications).setContentDescription(notices == 0 ? getString(R.string.notices_title)
            : "Хабарландырулар · " + notices + " жаңа");
        String destination = passenger ? showNextTrip() : null;
        showWeather(destination);
    }

    private void showStaff(boolean operator) {
        LocalDate today = LocalDate.now(ASTANA);
        text(R.id.staff_identity, user.roleName() + " · " + user.name());
        text(R.id.staff_title, operator ? "Ұшуды басқару" : "Әуежай әкімшілігі");
        text(R.id.staff_date, today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " · Астана");
        if (operator) {
            db.ensureDemoSchedule();
            var flights = db.flights(0, "");
            flights.removeIf(flight -> !Instant.ofEpochMilli(flight.time()).atOffset(ASTANA).toLocalDate().equals(today));
            long disruptions = flights.stream().filter(flight -> flight.status() == 1 || flight.status() == 3).count();
            text(R.id.staff_first, flights.size() + " рейс\nБүгінгі ұшулар");
            click(R.id.staff_first, () -> openToday(false));
            text(R.id.staff_second, db.checkInQueue(user.id()).size() + " жолаушы\nТіркеу кезегі");
            click(R.id.staff_second, () -> startActivity(new Intent(this, StaffListActivity.class)
                .putExtra(StaffListActivity.EXTRA_QUEUE, true)));
            text(R.id.staff_incidents, "Кешіккен және тоқтаған  ·  " + disruptions);
            click(R.id.staff_incidents, () -> openToday(true));
        } else {
            var accounts = db.accounts(user.id());
            long active = accounts.stream().filter(Account::active).count();
            long open = db.tickets(user.id()).stream().filter(ticket -> ticket.state() != 2).count();
            text(R.id.staff_first, active + " белсенді\nАккаунттар");
            click(R.id.staff_first, () -> open(AdminActivity.class));
            text(R.id.staff_second, open + " ашық\nӨтінімдер");
            click(R.id.staff_second, () -> open(TicketsActivity.class));
        }
    }

    private void openToday(boolean disrupted) {
        startActivity(new Intent(this, FlightsActivity.class).putExtra(FlightsActivity.EXTRA_TODAY, true)
            .putExtra(FlightsActivity.EXTRA_DISRUPTED, disrupted));
    }

    private String showNextTrip() {
        long now = System.currentTimeMillis();
        for (Booking booking : db.bookings(user.id())) {
            if (!booking.upcoming(now)) continue;
            visible(R.id.trip_codes, true);
            FlightAdapter.bindRoute(findViewById(R.id.trip_codes), booking.city(), booking.direction());
            text(R.id.trip_route, booking.flight() + "  ·  " + FLIGHT_STATES[booking.status()]);
            text(R.id.trip_meta, time(booking.time()) + "  ·  Қақпа " + booking.gate()
                + (booking.seat() == null ? "" : "  ·  Орын " + booking.seat()));
            text(R.id.trip_progress, "Дайындық " + Integer.bitCount(booking.tasks()) + "/4  ·  Талонды ашу  →");
            click(R.id.next_trip, () -> startActivity(new Intent(this, BookingsActivity.class)
                .putExtra(BookingsActivity.EXTRA_BOOKING, booking.id())));
            return booking.city();
        }
        visible(R.id.trip_codes, false);
        text(R.id.trip_route, "АЛДАҒЫ БРОНЬ");
        text(R.id.trip_meta, getString(R.string.trip_empty_title));
        text(R.id.trip_progress, "Рейс іздеу  →");
        click(R.id.next_trip, () -> open(FlightsActivity.class));
        return null;
    }

    private void openWeather(String city) {
        startActivity(new Intent(this, WeatherActivity.class).putExtra(WeatherActivity.EXTRA_CITY, city));
    }

    private void showWeather(String destination) {
        int request = ++weatherRequest;
        text(R.id.weather, "Астана\nЖүктелуде…");
        WeatherData.load(this, WeatherData.ASTANA_CITY, false, report -> {
            if (isFinishing() || isDestroyed() || request != weatherRequest) return;
            text(R.id.weather, "Астана\n" + (report == null ? "Дерек жоқ" : WeatherData.summary(report)));
        });
        click(R.id.destination_weather_chip, () -> openWeather(destination));
        text(R.id.destination_weather_chip, destination == null ? "Бағыттағы ауа райы\nҚаланы таңдау  →" : destination + "\nЖүктелуде…");
        if (destination != null) {
            WeatherData.load(this, destination, false, report -> {
                if (isFinishing() || isDestroyed() || request != weatherRequest) return;
                text(R.id.destination_weather_chip, destination + "\n" + (report == null ? "Дерек жоқ" : WeatherData.summary(report)));
            });
        }
    }

    private void guide() {
        new AlertDialog.Builder(this).setTitle(R.string.travel_guide)
            .setItems(new String[]{"Багажды дайындау", "Ұшуға дайындық", "Арнайы көмек"}, (dialog, item) -> {
                String[] advice = {
                    "Қол жүгі мен тіркелетін багаждың өлшемі мен салмағын әуе компаниясының бронь растауынан қараңыз.",
                    "Құжатыңызды дайындап, терминал мен қақпаны ресми таблодан тексеріңіз. Әуежайға баратын жолды Google Maps арқылы ашуға болады.",
                    "Арбамен жүру немесе басқа көмек қажет болса, әуе компаниясына алдын ала хабарласыңыз. Бұл қолданбада «Хабарлар» арқылы да өтінім қалдыра аласыз."
                };
                var details = new AlertDialog.Builder(this).setTitle(R.string.travel_guide)
                    .setMessage(advice[item]).setNegativeButton("Жабу", null);
                if (item == 1) details.setPositiveButton("Google Maps", (d, b) -> external(MAPS, "com.google.android.apps.maps"));
                if (item == 2) details.setPositiveButton("Хабарлар", (d, b) -> open(TicketsActivity.class));
                details.show();
            }).setNegativeButton("Жабу", null).show();
    }

    private void menu() {
        var popup = new android.widget.PopupMenu(this, findViewById(R.id.more));
        popup.inflate(R.menu.home_menu);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_map) external(MAPS, "com.google.android.apps.maps");
            else if (item.getItemId() == R.id.menu_board) external("https://www.nn-airport.kz/flight-status", null);
            else if (item.getItemId() == R.id.menu_about) open(AboutActivity.class);
            return true;
        });
        popup.show();
    }

    private void checkIn() {
        if (!signedIn(OPERATOR)) return;
        EditText input = new EditText(this);
        input.setHint("NQZ-XXXXXXXX");
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Броньды тіркеу").setView(input)
            .setNegativeButton("Артқа", null).setPositiveButton("Тіркеу", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (db.checkIn(user.id(), input.getText().toString().trim())) { toast("Жолаушы тіркелді."); dialog.dismiss(); }
            else input.setError("Бронь коды жарамсыз немесе тіркеліп қойған.");
        }));
        dialog.show();
    }
}
