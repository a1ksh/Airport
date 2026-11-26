package kz.edu.airportcourse.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebView;

import kz.edu.airportcourse.R;

public final class RouteMapActivity extends BaseActivity {
    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_route_map);
        WebView map = findViewById(R.id.route_map);
        map.getSettings().setAllowFileAccess(false);
        map.getSettings().setAllowContentAccess(false);
        map.getSettings().setBlockNetworkLoads(true);
        map.getSettings().setJavaScriptEnabled(true);
        map.loadUrl("file:///android_asset/route/wayfinding.html");
    }
}
