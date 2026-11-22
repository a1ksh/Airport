package kz.edu.airportcourse.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.WeatherData;
import static kz.edu.airportcourse.data.WeatherData.*;

public final class WeatherActivity extends BaseActivity {
    static final String EXTRA_CITY = "weather_city";
    private final List<String> cities = new ArrayList<>();
    private String city;
    private int destinationRequest;
    private int astanaRequest;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_weather);
        if (!signedIn(null)) return;
        db.ensureDemoSchedule();
        var unique = new LinkedHashSet<String>();
        String requested = state == null ? getIntent().getStringExtra(EXTRA_CITY) : state.getString(EXTRA_CITY);
        if (requested != null && !requested.trim().isEmpty() && !isAstana(requested)) unique.add(requested);
        for (var flight : db.flights(0, "")) if (!isAstana(flight.city())) unique.add(flight.city());
        for (var flight : db.flights(1, "")) if (!isAstana(flight.city())) unique.add(flight.city());
        cities.add(ASTANA_CITY);
        cities.addAll(unique);
        int index = requested != null && isAstana(requested) ? 0 : Math.min(1, cities.size() - 1);
        city = cities.get(index);
        Spinner selector = findViewById(R.id.weather_destination);
        spinner(selector, cities.toArray(new String[0]));
        selector.setSelection(index);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = cities.get(position);
                if (selected.equals(city)) return;
                city = selected;
                loadDestination(false);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        click(R.id.refresh, () -> {
            loadAstana(true);
            loadDestination(true);
        });
        click(R.id.weather_source, () -> external("https://open-meteo.com/en/docs", null));
        loadAstana(false);
        loadDestination(false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(EXTRA_CITY, city);
        super.onSaveInstanceState(state);
    }

    private void loadAstana(boolean refresh) {
        View card = findViewById(R.id.astana_weather);
        loading(card, ASTANA_CITY + " · NQZ");
        int request = ++astanaRequest;
        WeatherData.load(this, ASTANA_CITY, refresh, report -> {
            if (isFinishing() || isDestroyed() || request != astanaRequest) return;
            render(card, report);
        });
    }

    private void loadDestination(boolean refresh) {
        boolean astana = isAstana(city);
        View card = findViewById(R.id.destination_weather);
        card.setVisibility(astana ? View.GONE : View.VISIBLE);
        if (!astana) loading(card, city);
        text(R.id.forecast_title, city + " · 5 күндік болжам");
        text(R.id.forecast_empty, "Болжам жүктелуде…");
        visible(R.id.forecast_empty, true);
        LinearLayout days = findViewById(R.id.weather_days);
        days.removeAllViews();
        visible(R.id.weather_days, false);
        int request = ++destinationRequest;
        WeatherData.load(this, city, refresh, report -> {
            if (isFinishing() || isDestroyed() || request != destinationRequest) return;
            if (!astana) render(card, report);
            if (report == null || report.forecast().isEmpty()) {
                text(R.id.forecast_empty, "Болжам қолжетімсіз. Қайта жаңартып көріңіз.");
                return;
            }
            visible(R.id.forecast_empty, false);
            visible(R.id.weather_days, true);
            for (Day day : report.forecast()) {
                View row = getLayoutInflater().inflate(R.layout.row_weather_day, days, false);
                set(row, R.id.forecast_date, day.date().substring(8) + "." + day.date().substring(5, 7));
                set(row, R.id.forecast_status, description(day.code()));
                set(row, R.id.forecast_range, degree(day.low()) + " / " + degree(day.high()));
                days.addView(row);
            }
        });
    }

    private static void loading(View card, String label) {
        set(card, R.id.weather_city_name, label);
        set(card, R.id.weather_country, "");
        set(card, R.id.degree, "—");
        set(card, R.id.condition, "Жүктелуде…");
        set(card, R.id.weather_detail, "");
        set(card, R.id.weather_time, "");
    }

    private static void render(View card, Report report) {
        if (report == null) {
            set(card, R.id.condition, "Дерек қолжетімсіз");
            set(card, R.id.weather_detail, "Интернетті тексеріңіз.");
            return;
        }
        set(card, R.id.weather_country, report.country());
        set(card, R.id.degree, degree(report.degrees()) + "C");
        set(card, R.id.condition, description(report.code()));
        set(card, R.id.weather_detail, "Сезілуі " + degree(report.feels())
            + (Double.isFinite(report.wind()) ? "\nЖел " + Math.round(report.wind()) + " км/сағ" : ""));
        set(card, R.id.weather_time, (report.stale() ? "Сақталған\n" : "") + report.time() + " · жергілікті");
        View value = card.findViewById(R.id.degree);
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            value.setAlpha(.45f);
            value.setTranslationY(6f);
            value.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    private static void set(View root, int id, String text) {
        ((TextView) root.findViewById(id)).setText(text);
    }
}
