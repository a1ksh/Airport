package kz.edu.airportcourse.ui;

import android.net.Uri;
import android.os.Bundle;

import kz.edu.airportcourse.R;

public final class AboutActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_about);
        String airport = getString(R.string.about_airport_name) + ", " + getString(R.string.about_airport_address);
        Uri map = Uri.parse("https://www.google.com/maps/search/").buildUpon()
                .appendQueryParameter("api", "1").appendQueryParameter("query", airport).build();
        click(R.id.about_view_map, () -> external(map.toString(), "com.google.android.apps.maps"));
        click(R.id.about_route, () -> open(RouteMapActivity.class));
    }
}
