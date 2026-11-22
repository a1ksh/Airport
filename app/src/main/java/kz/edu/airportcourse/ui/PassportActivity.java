package kz.edu.airportcourse.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import kz.edu.airportcourse.R;
import static kz.edu.airportcourse.data.AirportDb.*;

/** A small passport view backed by operator-confirmed SQLite snapshots. */
public final class PassportActivity extends BaseActivity {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private String selectedCity;
    private List<TravelStamp> trips;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_passport);
        if (state != null) selectedCity = state.getString("passport_city");
    }

    @Override protected void onResume() {
        super.onResume();
        if (!signedIn(PASSENGER)) return;
        trips = db.travelStamps(user.id());
        var cities = db.passportCities(user.id());
        var countries = new HashSet<String>();
        for (var city : cities) {
            String country = cityCountry(city.city());
            if (!country.isEmpty()) countries.add(country);
        }
        text(R.id.passport_name, user.name());
        text(R.id.passport_stats, getString(R.string.passport_stats, trips.size(), cities.size(), countries.size()));
        visible(R.id.passport_empty, cities.isEmpty());
        LinearLayout stamps = findViewById(R.id.passport_stamps);
        stamps.removeAllViews();
        if (cities.stream().noneMatch(city -> city.city().equals(selectedCity))) {
            selectedCity = cities.isEmpty() ? null : cities.get(0).city();
        }
        LinearLayout row = null;
        for (int i = 0; i < cities.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setBaselineAligned(false);
                stamps.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            var city = cities.get(i);
            View cell = getLayoutInflater().inflate(R.layout.row_passport_stamp, row, false);
            row.addView(cell);
            Button button = cell.findViewById(R.id.stamp_button);
            button.setText(airportCode(city.city()));
            button.setSelected(city.city().equals(selectedCity));
            button.setContentDescription(getString(R.string.passport_stamp_description, city.city(), city.trips()));
            ((TextView) cell.findViewById(R.id.stamp_city)).setText(city.city());
            ((TextView) cell.findViewById(R.id.stamp_count)).setText(getString(R.string.passport_stamp_count, city.trips()));
            button.setOnClickListener(view -> {
                selectedCity = city.city();
                for (int y = 0; y < stamps.getChildCount(); y++) {
                    LinearLayout line = (LinearLayout) stamps.getChildAt(y);
                    for (int x = 0; x < line.getChildCount(); x++) {
                        Button other = line.getChildAt(x).findViewById(R.id.stamp_button);
                        if (other != null) other.setSelected(other == button);
                    }
                }
                showTrips();
            });
        }
        // Keep the last row's circles the same size as the preceding rows.
        if (row != null) for (int i = cities.size() % 3; i > 0 && i < 3; i++) {
            View space = new View(this);
            row.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        }
        showTrips();
    }

    private void showTrips() {
        visible(R.id.passport_city_title, selectedCity != null);
        LinearLayout list = findViewById(R.id.passport_trips);
        list.removeAllViews();
        if (selectedCity == null) return;
        text(R.id.passport_city_title, getString(R.string.passport_city_trips, selectedCity));
        for (var trip : trips) {
            if (!trip.city().equals(selectedCity)) continue;
            View card = getLayoutInflater().inflate(R.layout.row_card, list, false);
            ((TextView) card.findViewById(R.id.row_tag)).setText(Instant.ofEpochMilli(trip.departure()).atOffset(ASTANA).format(DATE));
            ((TextView) card.findViewById(R.id.row_title)).setText(trip.flightNumber());
            ((TextView) card.findViewById(R.id.row_body)).setText(getString(R.string.passport_trip_details,
                "Астана", trip.city(), trip.code(), trip.seat() == null ? "—" : trip.seat()));
            ((TextView) card.findViewById(R.id.row_badge)).setText(R.string.passport_trip_done);
            var layout = (LinearLayout.LayoutParams) card.getLayoutParams();
            layout.bottomMargin = Math.round(8 * getResources().getDisplayMetrics().density);
            list.addView(card, layout);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("passport_city", selectedCity);
        super.onSaveInstanceState(state);
    }
}
