package kz.edu.airportcourse.ui;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Locale;

import kz.edu.airportcourse.R;
import kz.edu.airportcourse.data.AirportDb.Review;
import static kz.edu.airportcourse.data.AirportDb.*;

/** Passengers send feedback; the administrator sees all feedback and grouped ratings. */
public final class ReviewsActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_reviews);
        if (!allowed()) return;
        click(R.id.review_compose, () -> {
            if (getFragmentManager().findFragmentByTag("review_compose") == null) {
                new ReviewDialogFragment().show(getFragmentManager(), "review_compose");
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (allowed()) render();
    }

    private boolean allowed() {
        if (!signedIn(null)) return false;
        if (user.role().equals(PASSENGER) || user.role().equals(ADMIN)) return true;
        finish();
        return false;
    }

    void render() {
        if (!allowed()) return;
        boolean admin = user.role().equals(ADMIN);
        visible(R.id.review_compose, !admin);
        text(R.id.review_description, getString(admin ? R.string.review_admin_hint : R.string.review_passenger_hint));
        var rows = new ArrayList<CardAdapter.Row>();
        if (admin) {
            var reviews = db.reviews(user.id());
            var groups = db.ratingDistribution(user.id());
            int total = 0, stars = 0;
            StringBuilder distribution = new StringBuilder();
            for (int rating = 5; rating >= 1; rating--) {
                final int value = rating;
                int count = groups.stream().filter(group -> group.rating() == value)
                    .mapToInt(group -> group.count()).sum();
                total += count;
                stars += rating * count;
                if (distribution.length() > 0) distribution.append("   ·   ");
                distribution.append(rating).append("★ ").append(count);
            }
            text(R.id.review_summary, total == 0 ? getString(R.string.review_no_ratings)
                : String.format(Locale.ROOT, "%.1f / 5  ·  %d пікір", (double) stars / total, total));
            text(R.id.review_distribution, distribution.toString());
            for (Review review : reviews) rows.add(row(review, review.name()));
        } else {
            Review latest = db.review(user.id());
            text(R.id.review_summary, getString(R.string.review_your_latest));
            text(R.id.review_distribution, getString(R.string.review_private_hint));
            if (latest != null) rows.add(row(latest, getString(R.string.review_thanks)));
        }
        visible(R.id.empty, rows.isEmpty());
        cards(R.id.review_list, new CardAdapter(this, rows));
    }

    private CardAdapter.Row row(Review review, String title) {
        return new CardAdapter.Row(time(review.updated()), title, review.body(), review.rating() + " / 5 ★");
    }
}
