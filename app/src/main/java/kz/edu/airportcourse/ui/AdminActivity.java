package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.Locale;
import kz.edu.airportcourse.R;
import static kz.edu.airportcourse.data.AirportDb.*;

public final class AdminActivity extends BaseActivity {
    private static final String[] FILTERS = {"Барлық рөлдер", "Жолаушылар", "Операторлар", "Әкімшілер", "Бұғатталғандар"};
    private int filter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_admin);
        click(R.id.add_operator, this::addOperator);
        filter = state == null ? 0 : state.getInt("account_filter");
        click(R.id.account_filter, () -> new AlertDialog.Builder(this).setTitle("Аккаунт сүзгісі")
            .setSingleChoiceItems(FILTERS, filter, (dialog, index) -> {
                filter = index;
                dialog.dismiss();
                render();
            }).setNegativeButton("Кері", null).show());
        EditText search = findViewById(R.id.account_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (user != null) render();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        search.setOnEditorActionListener((view, action, event) -> { hideKeyboard(); return true; });
    }

    @Override protected void onResume() {
        super.onResume();
        if (signedIn(ADMIN)) render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("account_filter", filter);
        super.onSaveInstanceState(state);
    }

    private void render() {
        var accounts = db.accounts(user.id());
        var matches = new ArrayList<Account>();
        var rows = new ArrayList<CardAdapter.Row>();
        int active = 0;
        String term = value(R.id.account_search).toLowerCase(Locale.ROOT);
        for (var person : accounts) {
            if (person.active()) active++;
            if (!matchesFilter(person) || (!person.name().toLowerCase(Locale.ROOT).contains(term)
                    && !person.email().toLowerCase(Locale.ROOT).contains(term))) continue;
            matches.add(person);
            rows.add(new CardAdapter.Row(person.roleName(), person.name(), person.email(),
                person.active() ? "Белсенді" : "Бұғатталған"));
        }
        text(R.id.account_filter, FILTERS[filter]);
        text(R.id.stats, accounts.size() + " аккаунт  ·  " + active + " белсенді");
        visible(R.id.account_empty, matches.isEmpty());
        cards(R.id.account_list, new CardAdapter(this, rows));
        ((ListView) findViewById(R.id.account_list)).setOnItemClickListener((parent, view, position, id) -> {
            hideKeyboard();
            var selected = matches.get(position);
            if (selected.role().equals(ADMIN)) return;
            new AlertDialog.Builder(this).setTitle(selected.name()).setMessage(selected.email())
                .setNegativeButton("Артқа", null).setPositiveButton(selected.active() ? "Бұғаттау" : "Қайта ашу", (d, b) -> {
                    db.setActive(user.id(), selected.id(), !selected.active());
                    render();
                }).show();
        });
    }

    private boolean matchesFilter(Account person) {
        return switch (filter) {
            case 1 -> person.role().equals(PASSENGER);
            case 2 -> person.role().equals(OPERATOR);
            case 3 -> person.role().equals(ADMIN);
            case 4 -> !person.active();
            default -> true;
        };
    }

    private void addOperator() {
        if (!signedIn(ADMIN)) return;
        View form = getLayoutInflater().inflate(R.layout.dialog_add_operator, null);
        EditText name = form.findViewById(R.id.operator_name);
        EditText email = form.findViewById(R.id.operator_email);
        EditText password = form.findViewById(R.id.operator_password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Оператор аккаунты")
            .setView(form).setNegativeButton("Кері", null).setPositiveButton("Қосу", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String display = name.getText().toString().trim();
            String address = email.getText().toString().trim();
            String secret = password.getText().toString();
            if (display.isEmpty()) { name.setError("Аты-жөнін енгізіңіз."); return; }
            if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) { email.setError("Email мекенжайын тексеріңіз."); return; }
            if (secret.length() < 8) { password.setError("Кемінде 8 таңба енгізіңіз."); return; }
            if (!db.addOperator(user.id(), display, address, secret)) { email.setError("Бұл email тіркелген."); return; }
            dialog.dismiss();
            filter = 2;
            ((EditText) findViewById(R.id.account_search)).setText("");
            render();
            toast("Оператор қосылды.");
        }));
        dialog.show();
    }
}
