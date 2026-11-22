package kz.edu.airportcourse.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.List;
import kz.edu.airportcourse.R;

/** One XML row reused by the flight, account, message and request lists. */
final class CardAdapter extends ArrayAdapter<CardAdapter.Row> {
    record Row(String tag, String title, String body, String badge) {}

    CardAdapter(Context context, List<Row> rows) { super(context, R.layout.row_card, rows); }

    @Override public View getView(int position, View view, ViewGroup parent) {
        if (view == null) view = LayoutInflater.from(getContext()).inflate(R.layout.row_card, parent, false);
        Row row = getItem(position);
        display(view, R.id.row_tag, row.tag);
        display(view, R.id.row_title, row.title);
        display(view, R.id.row_body, row.body);
        display(view, R.id.row_badge, row.badge);
        return view;
    }

    private void display(View view, int id, String value) {
        TextView label = view.findViewById(id);
        label.setText(value);
        label.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }
}
