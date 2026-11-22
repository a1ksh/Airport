package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Collections;
import java.util.List;
import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.AirportDb.Booking;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class BookingsActivity extends BaseActivity {
    static final String EXTRA_BOOKING = "booking_flight";
    private static final int[] TASK_FIELDS = {R.id.task_documents, R.id.task_baggage, R.id.task_transport, R.id.task_pass};
    private boolean archive;
    private long initialBooking;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_bookings);
        archive = state != null && state.getBoolean("archive");
        initialBooking = state == null ? getIntent().getLongExtra(EXTRA_BOOKING, 0) : state.getLong(EXTRA_BOOKING);
        click(R.id.upcoming_trips, () -> { archive = false; render(); });
        click(R.id.past_trips, () -> { archive = true; render(); });
        click(R.id.find_trip, () -> open(FlightsActivity.class));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!signedIn(PASSENGER)) return;
        if (initialBooking != 0) {
            Booking booking = db.booking(user.id(), initialBooking);
            initialBooking = 0;
            if (booking != null) {
                archive = !booking.upcoming(System.currentTimeMillis());
                showPass(this, booking, this::render);
            }
        }
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("archive", archive);
        state.putLong(EXTRA_BOOKING, initialBooking);
        super.onSaveInstanceState(state);
    }

    private void render() {
        var bookings = db.bookings(user.id());
        long now = System.currentTimeMillis();
        bookings.removeIf(booking -> booking.upcoming(now) == archive);
        if (archive) Collections.reverse(bookings);
        tab(R.id.upcoming_trips, !archive);
        tab(R.id.past_trips, archive);
        text(R.id.empty, archive ? "Өткен немесе тоқтатылған рейс жоқ." : "Алдағы бронь жоқ.");
        visible(R.id.empty, bookings.isEmpty());
        visible(R.id.find_trip, !archive && bookings.isEmpty());
        ListView list = findViewById(R.id.booking_list);
        list.setAdapter(new TripAdapter(this, bookings));
        list.setOnItemClickListener((p,v,position,id) -> showPass(this, bookings.get(position), this::render));
    }

    private void tab(int id, boolean selected) {
        Button button = findViewById(id);
        button.setSelected(selected);
        button.setBackgroundResource(selected ? R.drawable.primary : R.drawable.secondary);
        button.setTextColor(selected ? android.graphics.Color.WHITE : getColor(R.color.blue));
    }

    static void showPass(BaseActivity app, Booking booking, Runnable reload) {
        View view = app.getLayoutInflater().inflate(R.layout.dialog_booking, null);
        FlightAdapter.bindRoute(view, booking.city(), booking.direction());
        ((TextView) view.findViewById(R.id.booking_info)).setText(booking.flight());
        ((TextView) view.findViewById(R.id.booking_when)).setText(app.getString(R.string.booking_when_format,
            time(booking.time()), booking.gate(), seatLabel(booking)));
        ((TextView) view.findViewById(R.id.booking_code)).setText(booking.code());
        ((TextView) view.findViewById(R.id.booking_status)).setText(status(booking));
        Button preparation = view.findViewById(R.id.prepare_trip);
        preparation.setVisibility(booking.upcoming(System.currentTimeMillis()) ? View.VISIBLE : View.GONE);
        preparation.setText(tasksLabel(booking.tasks()));
        preparation.setOnClickListener(v -> showTasks(app, booking.id(), preparation, reload));
        view.findViewById(R.id.share_booking).setOnClickListener(v -> {
            String summary = "Airport · Демо бронь\n" + booking.route() + "\n" + booking.flight() + " · "
                + time(booking.time()) + " · Қақпа " + booking.gate() + seatLabel(booking)
                + "\n" + booking.code() + " · " + status(booking);
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summary);
            app.startActivity(Intent.createChooser(share, app.getString(R.string.share_booking)));
        });
        ImageView qr = view.findViewById(R.id.qr);
        try {
            int size = 320;
            var matrix = new QRCodeWriter().encode(booking.code(), BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                pixels[y * size + x] = matrix.get(x, y) ? 0xff10284a : 0xffffffff;
            qr.setImageBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888));
        } catch (com.google.zxing.WriterException e) { qr.setVisibility(View.GONE); }
        var dialog = new AlertDialog.Builder(app).setView(view).setPositiveButton("Дайын", null);
        if (!booking.checked()) dialog.setNeutralButton("Бас тарту", (d, which) ->
            new AlertDialog.Builder(app).setTitle("Броньды жою керек пе?").setMessage(booking.code())
                .setNegativeButton("Кері", null).setPositiveButton("Жою", (confirm, button) -> {
                    if (app.db.cancelBooking(app.user.id(), booking.id())) app.toast("Бронь жойылды.");
                    else app.toast("Бронь өзгерген. Тізімді қайта ашыңыз.");
                    reload.run();
                }).show());
        dialog.show();
    }

    private static void showTasks(BaseActivity app, long flight, Button summary, Runnable reload) {
        Booking booking = app.db.booking(app.user.id(), flight);
        if (booking == null) { app.toast("Бронь табылмады."); return; }
        View form = app.getLayoutInflater().inflate(R.layout.dialog_trip_tasks, null);
        ((TextView) form.findViewById(R.id.trip_tasks_route)).setText(app.getString(R.string.trip_tasks_route_format,
            booking.flight(), booking.route()));
        for (int i = 0; i < TASK_FIELDS.length; i++) {
            ((CheckBox) form.findViewById(TASK_FIELDS[i])).setChecked((booking.tasks() & (1 << i)) != 0);
        }
        new AlertDialog.Builder(app).setTitle(R.string.trip_preparation).setView(form).setNegativeButton("Кері", null)
            .setPositiveButton("Сақтау", (dialog, action) -> {
                int tasks = 0;
                for (int i = 0; i < TASK_FIELDS.length; i++) {
                    if (((CheckBox) form.findViewById(TASK_FIELDS[i])).isChecked()) tasks |= 1 << i;
                }
                if (!app.db.saveTripTasks(app.user.id(), flight, tasks)) {
                    app.toast("Бронь табылмады.");
                    return;
                }
                summary.setText(tasksLabel(tasks));
                reload.run();
            }).show();
    }

    private static String tasksLabel(int tasks) {
        return "Ұшуға дайындық · " + Integer.bitCount(tasks) + "/" + TASK_FIELDS.length;
    }

    private static String seatLabel(Booking booking) {
        return booking.seat() == null ? "" : " · Орын " + booking.seat();
    }

    private static String status(Booking booking) {
        if (booking.status() == 3) return "Рейс тоқтатылды";
        if (booking.checked()) return "Тіркелді";
        return booking.time() < System.currentTimeMillis() ? "Уақыты өтті" : "Расталған бронь";
    }

    private static final class TripAdapter extends ArrayAdapter<Booking> {
        TripAdapter(BookingsActivity page, List<Booking> bookings) {
            super(page, R.layout.row_flight, bookings);
        }

        @Override public View getView(int position, View row, ViewGroup parent) {
            if (row == null) row = LayoutInflater.from(getContext()).inflate(R.layout.row_flight, parent, false);
            Booking booking = getItem(position);
            FlightAdapter.bindRoute(row, booking.city(), booking.direction());
            ((TextView) row.findViewById(R.id.flight_number)).setText(getContext().getString(R.string.trip_flight_time_format,
                booking.flight(), time(booking.time())));
            ((TextView) row.findViewById(R.id.flight_badge)).setText(status(booking));
            String preparation = booking.upcoming(System.currentTimeMillis())
                ? getContext().getString(R.string.trip_tasks_suffix_format, tasksLabel(booking.tasks())) : "";
            ((TextView) row.findViewById(R.id.flight_meta)).setText(getContext().getString(R.string.trip_meta_format,
                booking.gate(), seatLabel(booking), booking.code(), preparation));
            return row;
        }
    }
}
