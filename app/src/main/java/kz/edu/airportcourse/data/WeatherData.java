package kz.edu.airportcourse.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class WeatherData {
    public static final String ASTANA_CITY = "Астана";
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private record Place(double lat, double lon, String country) {}
    public record Day(String date, int code, double low, double high) {}
    public record Report(String country, double degrees, double feels, double wind, int code,
                         String time, List<Day> forecast, boolean stale) {}

    private WeatherData() {}

    public static boolean isAstana(String city) {
        return ASTANA_CITY.equalsIgnoreCase(city.trim()) || "NQZ".equalsIgnoreCase(city.trim());
    }

    public static void load(Context context, String city, boolean force, Consumer<Report> callback) {
        SharedPreferences cache = context.getApplicationContext().getSharedPreferences("weather_cache", Context.MODE_PRIVATE);
        String key = city.trim().toLowerCase(Locale.ROOT);
        NETWORK.execute(() -> {
            Report result;
            long age = System.currentTimeMillis() - cache.getLong(key + ":at", 0);
            try {
                if (!force && age >= 0 && age < 900_000L && cache.contains(key)) {
                    result = cached(cache, key, false);
                } else {
                    Place place = locate(city);
                    String url = Uri.parse("https://api.open-meteo.com/v1/forecast").buildUpon()
                        .appendQueryParameter("latitude", Double.toString(place.lat))
                        .appendQueryParameter("longitude", Double.toString(place.lon))
                        .appendQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
                        .appendQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min")
                        .appendQueryParameter("timezone", "auto").appendQueryParameter("forecast_days", "5").build().toString();
                    JSONObject json = fetch(url);
                    result = parse(json, place.country, false);
                    cache.edit().putString(key, json.put("country_label", place.country).toString())
                        .putLong(key + ":at", System.currentTimeMillis()).apply();
                }
            } catch (Exception failure) {
                result = null;
                if (age >= 0 && age < 48 * 3_600_000L && cache.contains(key)) {
                    try { result = cached(cache, key, true); }
                    catch (Exception ignored) { /* A corrupt cache cannot provide a report. */ }
                }
            }
            Report report = result;
            MAIN.post(() -> callback.accept(report));
        });
    }

    private static Report cached(SharedPreferences cache, String key, boolean stale) throws Exception {
        JSONObject json = new JSONObject(cache.getString(key, ""));
        return parse(json, json.optString("country_label"), stale);
    }

    private static Place locate(String city) throws Exception {
        String cityKey = city.trim().toLowerCase(Locale.ROOT);
        Place place = switch (cityKey) {
            case "астана", "astana", "nqz" -> new Place(51.0222, 71.4669, "Қазақстан");
            case "алматы", "almaty", "ala" -> new Place(43.2389, 76.8897, "Қазақстан");
            case "шымкент", "shymkent", "cit" -> new Place(42.3417, 69.5901, "Қазақстан");
            case "ақтау", "актау", "aktau", "sco" -> new Place(43.6532, 51.1975, "Қазақстан");
            case "атырау", "atyrau", "guw" -> new Place(47.1068, 51.9160, "Қазақстан");
            case "ташкент", "tashkent", "tas" -> new Place(41.2995, 69.2401, "Өзбекстан");
            case "ыстанбұл", "стамбул", "istanbul", "ist" -> new Place(41.0082, 28.9784, "Түркия");
            case "дубай", "dubai", "dxb" -> new Place(25.2048, 55.2708, "БАӘ");
            default -> null;
        };
        if (place != null) return place;
        String query = Uri.parse("https://geocoding-api.open-meteo.com/v1/search").buildUpon()
            .appendQueryParameter("name", city.trim()).appendQueryParameter("count", "1")
            .appendQueryParameter("language", "kk").build().toString();
        var results = fetch(query).optJSONArray("results");
        if (results == null || results.length() == 0) throw new IOException("City unavailable");
        JSONObject location = results.getJSONObject(0);
        return new Place(location.getDouble("latitude"), location.getDouble("longitude"), location.optString("country"));
    }

    private static JSONObject fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5500);
        connection.setReadTimeout(6500);
        connection.setRequestProperty("Accept", "application/json");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) response.append(line);
            return new JSONObject(response.toString());
        } finally { connection.disconnect(); }
    }

    private static Report parse(JSONObject json, String country, boolean stale) throws Exception {
        var current = json.getJSONObject("current");
        var daily = json.optJSONObject("daily");
        List<Day> days = new ArrayList<>();
        if (daily != null) {
            var dates = daily.getJSONArray("time");
            for (int i = 0; i < dates.length(); i++) {
                double low = daily.getJSONArray("temperature_2m_min").optDouble(i);
                double high = daily.getJSONArray("temperature_2m_max").optDouble(i);
                if (Double.isFinite(low) && Double.isFinite(high)) {
                    days.add(new Day(dates.getString(i), daily.getJSONArray("weather_code").optInt(i, -1), low, high));
                }
            }
        }
        double temp = current.getDouble("temperature_2m");
        if (!Double.isFinite(temp)) throw new IOException("Temperature unavailable");
        return new Report(country, temp, current.optDouble("apparent_temperature"), current.optDouble("wind_speed_10m"),
            current.optInt("weather_code", -1), current.getString("time").replace('T', ' '), days, stale);
    }

    public static String degree(double value) {
        return Double.isFinite(value) ? Long.toString(Math.round(value)) + "°" : "—";
    }

    public static String description(int code) {
        if (code < 0) return "Белгісіз";
        if (code == 0) return "Ашық";
        if (code <= 3) return "Бұлтты";
        if (code <= 48) return "Тұман";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Қар";
        return code >= 95 ? "Найзағай" : "Жауын";
    }

    public static String summary(Report report) {
        return degree(report.degrees) + "C  ·  " + description(report.code)
            + (report.stale ? "  ·  Сақталған" : "");
    }
}
