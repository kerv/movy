package sx.movy.tv;

import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

/** Native Media3 player. It owns video playback; the resolver WebView is gone
 * before this activity appears. */
public class PlayerActivity extends android.app.Activity {
    private ExoPlayer player;
    private PlayerView playerView;

    @Override @UnstableApi public void onCreate(@Nullable Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerView = new PlayerView(this); playerView.setUseController(true); setContentView(playerView);
        Uri stream = getIntent().getData(); if (stream == null) { finish(); return; }
        String referer = getIntent().getStringExtra("referer");
        String cookie = getIntent().getStringExtra("cookie");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent("MovyTV/2.0");
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        if (referer != null) headers.put("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        if (!headers.isEmpty()) http.setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(this).build(); playerView.setPlayer(player);
        HlsMediaSource source = new HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(stream));
        player.setMediaSource(source); player.prepare(); player.play();
        player.addListener(new Player.Listener() { @Override public void onPlayerError(PlaybackException error) {
            Toast.makeText(PlayerActivity.this, "Stream expired or is unavailable. Go back and try Play again.", Toast.LENGTH_LONG).show();
        }});
    }
    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { if (player != null) { player.release(); player = null; } super.onDestroy(); }
}
