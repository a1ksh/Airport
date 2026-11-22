package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import java.util.ArrayList;
import kz.edu.airportcourse.R;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class TicketsActivity extends BaseActivity {
    static final String EXTRA_TICKET = "ticket_id";
    private static final String[] TOPICS = {"Рейс және бронь", "Багаж", "Арнайы көмек", "Басқа сұрақ"};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_tickets);
        click(R.id.new_ticket, this::createTicket);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!signedIn(null)) return;
        var passenger = user.role().equals(PASSENGER);
        text(R.id.ticket_heading, passenger ? "Менің өтінімдерім" : "Өтінімдер кезегі");
        text(R.id.ticket_hint, passenger ? "Сұрағыңызды жіберіп, жауапты осы жерден оқыңыз." :
            user.role().equals(OPERATOR) ? "Өтінімді ашып, жолаушыға жауап беріңіз." : "Жолаушылар мен операторлардың хабарларын қараңыз.");
        visible(R.id.new_ticket, passenger);

        var tickets = db.tickets(user.id());
        var rows = new ArrayList<CardAdapter.Row>();
        for (var t : tickets) {
            String badge = t.unread() == 0 ? TICKET_STATES[t.state()] : t.unread() + " жаңа";
            String from = passenger ? "" : t.name() + "  ·  ";
            rows.add(new CardAdapter.Row("#" + t.id(), t.subject(),
                from + TICKET_STATES[t.state()] + "  ·  " + time(t.updated()), badge));
        }
        visible(R.id.empty, rows.isEmpty());
        cards(R.id.ticket_list, new CardAdapter(this, rows));
        ((ListView) findViewById(R.id.ticket_list)).setOnItemClickListener((parent, view, position, id) ->
            chat(tickets.get(position).id()));
    }

    private void chat(long ticket) {
        startActivity(new Intent(this, ChatActivity.class).putExtra(EXTRA_TICKET, ticket));
    }

    private void createTicket() {
        View form = getLayoutInflater().inflate(R.layout.dialog_ticket, null);
        Spinner topic = form.findViewById(R.id.topic);
        EditText message = form.findViewById(R.id.ticket_text);
        spinner(topic, TOPICS);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Жаңа өтінім").setView(form)
            .setNegativeButton("Артқа", null).setPositiveButton("Жіберу", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String body = message.getText().toString().trim();
            if (body.isEmpty()) { message.setError("Сұрағыңызды жазыңыз."); return; }
            long ticket = db.createTicket(user.id(), (String) topic.getSelectedItem(), body);
            dialog.dismiss();
            toast("Өтінім жіберілді.");
            chat(ticket);
        }));
        dialog.show();
    }
}
