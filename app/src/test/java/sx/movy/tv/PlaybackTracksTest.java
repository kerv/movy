package sx.movy.tv;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MergingMediaSource;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackTracksTest {
    @Test public void sidecarCaptionsAreMergedWithVideoAndLanguagesAreReadable(){
        PlaybackTracks tracks=new PlaybackTracks("{\"subtitles\":[{\"url\":\"https://media.example/eng.vtt\",\"lang\":\"eng\",\"label\":\"eng\"},{\"url\":\"javascript:bad\"}],\"sources\":[{\"url\":\"https://media.example/4k.m3u8\",\"label\":\"2160p\"}]}");
        assertEquals(1,tracks.subtitles.size());assertEquals("English",tracks.subtitles.get(0).label);assertEquals("en",tracks.subtitles.get(0).language);
        MediaItem item=new MediaItem.Builder().setUri("https://media.example/video.m3u8").setMimeType(MimeTypes.APPLICATION_M3U8).setSubtitleConfigurations(tracks.subtitles).build();
        assertTrue(new DefaultMediaSourceFactory(new DefaultHttpDataSource.Factory()).createMediaSource(item) instanceof MergingMediaSource);
        assertEquals("Original audio",PlaybackTracks.languageLabel("```","und","Original audio"));
    }
}
