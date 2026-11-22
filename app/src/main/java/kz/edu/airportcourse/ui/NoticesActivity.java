package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ListView;

import java.util.ArrayList;

import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.AirportDb.Notice;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class NoticesActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_notices);
        click(R.id.notices_messages, () -> open(TicketsActivity.class));
        click(R.id.notices_read_all, () -> {
            db.readNotices(user.id(), null);
            render();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (signedIn(null)) render();
    }

    private void render() {
        var notices = db.notices(user.id());
        var rows = new ArrayList<CardAdapter.Row>();
        for (Notice notice : notices) {
            rows.add(new CardAdapter.Row(time(notice.created()), notice.title(), notice.body(), notice.seen() ? "" : "Жаңа"));
        }
        int messages = db.unread(user.id());
        text(R.id.notices_messages, messages == 0 ? "Қолдау хабарлары" : "Қолдау хабарлары · " + messages + " жаңа");
        visible(R.id.notices_read_all, notices.stream().anyMatch(notice -> !notice.seen()));
        visible(R.id.empty, rows.isEmpty());
        cards(R.id.notice_list, new CardAdapter(this, rows));
        ListView list = findViewById(R.id.notice_list);
        list.setOnItemClickListener((parent, view, position, id) -> show(notices.get(position)));
    }

    private void show(Notice notice) {
        db.readNotices(user.id(), notice.id());
        render();
        var dialog = new AlertDialog.Builder(this).setTitle(notice.title()).setMessage(notice.body())
            .setNegativeButton("Жабу", null).setNeutralButton("Өшіру", (d, b) -> {
                db.deleteNotice(user.id(), notice.id());
                render();
            });
        if (user.role().equals(PASSENGER) && db.booking(user.id(), notice.flight()) != null) {
            dialog.setPositiveButton("Сапарды ашу", (d, b) -> startActivity(new Intent(this, BookingsActivity.class)
                .putExtra(BookingsActivity.EXTRA_BOOKING, notice.flight())));
        }
        dialog.show();
    }
}
