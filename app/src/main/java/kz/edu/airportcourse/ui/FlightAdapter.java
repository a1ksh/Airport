package kz.edu.airportcourse.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.AirportDb.Flight;
import static kz.edu.airportcourse.data.AirportDb.*;

final class FlightAdapter extends ArrayAdapter<Flight> {
    private final Set<Long> saved;

    FlightAdapter(Context context, List<Flight> flights, Set<Long> saved) {
        super(context, 0, flights);
        this.saved = saved;
    }

    @Override public View getView(int position, View row, ViewGroup parent) {
        if (row == null) row = LayoutInflater.from(getContext()).inflate(R.layout.row_flight, parent, false);
        Flight flight = getItem(position);
        put(row, R.id.flight_number, flight.number() + (saved.contains(flight.id()) ? "  ★" : ""));
        bindFlight(row, flight);
        return row;
    }

    static void bindFlight(View row, Flight flight) {
        bindRoute(row, flight.city(), flight.direction());
        put(row, R.id.flight_meta, time(flight.time()) + "  ·  Қақпа " + flight.gate());
        put(row, R.id.flight_badge, flight.status() != 3 && flight.time() < System.currentTimeMillis()
            ? "Уақыты өтті" : FLIGHT_STATES[flight.status()]);
    }

    static void bindRoute(View row, String city, int direction) {
        String code = airportCode(city);
        put(row, R.id.from_code, direction == 0 ? "NQZ" : code);
        put(row, R.id.to_code, direction == 0 ? code : "NQZ");
        put(row, R.id.from_city, direction == 0 ? "Астана" : city);
        put(row, R.id.to_city, direction == 0 ? city : "Астана");
    }

    private static void put(View row, int id, String value) {
        ((TextView) row.findViewById(id)).setText(value);
    }
}
