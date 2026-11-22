package kz.edu.airportcourse.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.MediaController;
import android.widget.Spinner;
import android.widget.VideoView;

import kz.edu.airportcourse.R;

/** Local videos work without a network connection; playback follows the Activity lifecycle. */
public final class MediaActivity extends BaseActivity {
    private static final int[] CLIPS = {R.raw.travel_story, R.raw.flight_film};
    private VideoView video;
    private int selected, position;
    private boolean loaded, prepared, wantsPlayback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page(R.layout.activity_media);
        if (state != null) {
            selected = Math.max(0, Math.min(CLIPS.length - 1, state.getInt("clip", 0)));
            position = state.getInt("position", 0);
            wantsPlayback = state.getBoolean("playing", false);
        }
        video = findViewById(R.id.video_player);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setOnPreparedListener(player -> {
            prepared = true;
            video.seekTo(position > 0 ? position : 1);
            text(R.id.media_status, getString(R.string.media_ready));
            if (wantsPlayback) video.start();
        });
        video.setOnCompletionListener(player -> {
            wantsPlayback = false;
            position = 0;
            text(R.id.media_status, getString(R.string.media_finished));
        });
        video.setOnErrorListener((player, what, extra) -> {
            loaded = prepared = wantsPlayback = false;
            text(R.id.media_status, getString(R.string.media_error));
            toast(getString(R.string.media_error));
            return true;
        });
        Spinner clips = findViewById(R.id.media_selector);
        spinner(clips, getResources().getStringArray(R.array.media_titles));
        clips.setSelection(selected);
        clips.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                if (selected == index) return;
                selected = index;
                position = 0;
                wantsPlayback = false;
                loadClip();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        click(R.id.media_play, () -> {
            wantsPlayback = true;
            if (!loaded) loadClip();
            else if (prepared) video.start();
        });
        click(R.id.media_pause, () -> {
            wantsPlayback = false;
            if (prepared) {
                position = video.getCurrentPosition();
                video.pause();
            }
        });
        click(R.id.media_stop, () -> {
            wantsPlayback = false;
            position = 0;
            loadClip();
        });
    }

    private void loadClip() {
        video.stopPlayback();
        prepared = false;
        loaded = true;
        text(R.id.media_status, getString(R.string.media_loading));
        text(R.id.media_title, getResources().getStringArray(R.array.media_titles)[selected]);
        video.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + CLIPS[selected]));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!signedIn(null)) return;
        if (!loaded) loadClip();
        else if (prepared && wantsPlayback) video.start();
    }

    @Override protected void onPause() {
        if (video != null && prepared) {
            position = video.getCurrentPosition();
            wantsPlayback = video.isPlaying();
            video.pause();
        }
        super.onPause();
    }

    @Override protected void onStop() {
        if (video != null) video.stopPlayback();
        loaded = prepared = false;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("clip", selected);
        state.putInt("position", prepared ? video.getCurrentPosition() : position);
        state.putBoolean("playing", prepared ? video.isPlaying() : wantsPlayback);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
