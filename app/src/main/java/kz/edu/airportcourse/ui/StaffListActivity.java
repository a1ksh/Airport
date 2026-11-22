package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ListView;

import java.util.ArrayList;

import kz.edu.airportcourse.R;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class StaffListActivity extends BaseActivity {
    static final String EXTRA_QUEUE = "staff_queue";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_staff_list);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean queue = getIntent().getBooleanExtra(EXTRA_QUEUE, false);
        if (!signedIn(queue ? OPERATOR : null)) return;
        if (user.role().equals(PASSENGER)) {
            finish();
            return;
        }
        if (queue) showQueue();
        else showLog();
    }

    private void showQueue() {
        var waiting = db.checkInQueue(user.id());
        var rows = new ArrayList<CardAdapter.Row>();
        for (var entry : waiting) {
            rows.add(new CardAdapter.Row(entry.flight() + " · " + time(entry.time()), entry.name(),
                entry.city() + " · " + entry.code() + (entry.seat() == null ? "" : " · " + entry.seat()), "Күтуде"));
        }
        text(R.id.staff_list_title, "Тіркеу кезегі");
        text(R.id.staff_list_subtitle, "Бүгінгі ұшулар · " + waiting.size() + " жолаушы тіркелмеген");
        text(R.id.empty, "Тіркеуді күтіп тұрған жолаушы жоқ.");
        visible(R.id.empty, waiting.isEmpty());
        cards(R.id.staff_rows, new CardAdapter(this, rows));
        ListView list = findViewById(R.id.staff_rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            var entry = waiting.get(position);
            new AlertDialog.Builder(this).setTitle("Жолаушыны тіркеу")
                .setMessage(entry.name() + "\n" + entry.flight() + " · " + entry.code())
                .setNegativeButton("Кері", null).setPositiveButton("Тіркеу", (dialog, which) -> {
                    toast(db.checkIn(user.id(), entry.code()) ? "Жолаушы тіркелді." : "Бұл броньды қазір тіркеу мүмкін емес.");
                    showQueue();
                }).show();
        });
    }

    private void showLog() {
        var entries = db.operations(user.id());
        var rows = new ArrayList<CardAdapter.Row>();
        for (var action : entries) {
            rows.add(new CardAdapter.Row(time(action.time()), action.action(), action.detail(), action.actor()));
        }
        text(R.id.staff_list_title, "Әрекеттер журналы");
        text(R.id.staff_list_subtitle, user.role().equals(ADMIN) ? "Қызметкерлердің соңғы әрекеттері" : "Менің соңғы әрекеттерім");
        text(R.id.empty, "Журналда әзірге жазба жоқ.");
        visible(R.id.empty, entries.isEmpty());
        cards(R.id.staff_rows, new CardAdapter(this, rows));
    }
}
