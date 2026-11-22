package kz.edu.airportcourse.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RatingBar;

import kz.edu.airportcourse.R;
import static kz.edu.airportcourse.data.AirportDb.PASSENGER;

/** The framework restores this dialog with its Activity across configuration changes. */
public final class ReviewDialogFragment extends DialogFragment {
    private static final String RATING = "rating", BODY = "body";
    private RatingBar rating;
    private EditText body;

    @Override public Dialog onCreateDialog(Bundle state) {
        ReviewsActivity host = (ReviewsActivity) getActivity();
        View form = host.getLayoutInflater().inflate(R.layout.dialog_review, null);
        rating = form.findViewById(R.id.review_rating);
        body = form.findViewById(R.id.review_body);
        if (state != null) {
            rating.setRating(state.getFloat(RATING, 5));
            body.setText(state.getString(BODY, ""));
        }
        return new AlertDialog.Builder(host).setTitle(R.string.review_compose)
            .setView(form).setNegativeButton(R.string.review_cancel, null)
            .setPositiveButton(R.string.review_send, null).create();
    }

    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) getDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            ReviewsActivity host = (ReviewsActivity) getActivity();
            if (host == null || !host.signedIn(PASSENGER)) { dismiss(); return; }
            try {
                host.db.saveReview(host.user.id(), Math.round(rating.getRating()), body.getText().toString());
                host.hideKeyboard();
                host.toast(getString(R.string.review_sent));
                host.render();
                dismiss();
            } catch (IllegalArgumentException error) {
                body.setError(error.getMessage());
            }
        });
    }

    @Override public void onSaveInstanceState(Bundle state) {
        if (rating != null) state.putFloat(RATING, rating.getRating());
        if (body != null) state.putString(BODY, body.getText().toString());
        super.onSaveInstanceState(state);
    }
}
