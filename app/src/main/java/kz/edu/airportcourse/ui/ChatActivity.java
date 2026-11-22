package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ListView;
import java.util.ArrayList;
import kz.edu.airportcourse.R;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class ChatActivity extends BaseActivity {
    private long ticketId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_chat);
        ticketId = getIntent().getLongExtra(TicketsActivity.EXTRA_TICKET, -1);
        click(R.id.send, this::sendMessage);
        click(R.id.ticket_status, () -> new AlertDialog.Builder(this).setTitle("Өтінім мәртебесі")
            .setItems(TICKET_STATES, (d, index) -> { db.setTicketState(user.id(), ticketId, index); render(); }).show());
    }

    @Override protected void onResume() {
        super.onResume();
        if (signedIn(null)) render();
    }

    private void render() {
        try {
            var ticket = db.ticket(user.id(), ticketId);
            text(R.id.ticket_id, "ӨТІНІМ #" + ticket.id());
            text(R.id.subject, ticket.subject());
            text(R.id.status, TICKET_STATES[ticket.state()] + "  ·  " + ticket.name());
            visible(R.id.ticket_status, user.role().equals(OPERATOR));
            boolean closed = ticket.state() == 2;
            boolean canSend = !closed && !user.role().equals(ADMIN);
            visible(R.id.message, canSend);
            visible(R.id.send, canSend);
            visible(R.id.closed, closed);

            var rows = new ArrayList<CardAdapter.Row>();
            for (var m : db.openMessages(user.id(), ticketId)) {
                var tag = m.mine() ? "Сіз" : m.role().equals(OPERATOR) ? "Әуежай қолдауы" : m.sender();
                rows.add(new CardAdapter.Row(tag, "", m.body(), time(m.time())));
            }
            cards(R.id.chat_list, new CardAdapter(this, rows));
            ((ListView) findViewById(R.id.chat_list)).setSelection(Math.max(0, rows.size() - 1));
        } catch (IllegalStateException e) { toast(e.getMessage()); finish(); }
    }

    private void sendMessage() {
        String message = value(R.id.message);
        if (message.isEmpty()) { ((EditText) findViewById(R.id.message)).setError("Хабар жазыңыз."); return; }
        try {
            hideKeyboard();
            db.send(user.id(), ticketId, message);
            text(R.id.message, "");
            render();
        } catch (IllegalStateException e) { toast(e.getMessage()); }
    }
}
