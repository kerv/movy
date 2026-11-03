package sx.movy.tv;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PlaybackQualityTest {
    @Test public void preferencePersistsAndDefaultsToAuto(){
        NativeActivity activity=Robolectric.buildActivity(NativeActivity.class).get();
        assertEquals(PlaybackQuality.AUTO,PlaybackQuality.get(activity));
        PlaybackQuality.set(activity,720);assertEquals(720,PlaybackQuality.get(activity));
    }
    @Test public void capPrefersBestFitAndLowestWhenNoneFit(){
        assertTrue(PlaybackQuality.better(720,2160,720));
        assertFalse(PlaybackQuality.better(2160,720,720));
        assertTrue(PlaybackQuality.better(1080,2160,720));
        assertTrue(PlaybackQuality.better(1080,720,1080));
        assertFalse(PlaybackQuality.better(0,720,720));
        assertTrue(PlaybackQuality.better(2160,720,PlaybackQuality.HIGHEST));
    }
    @Test public void advertisedSourceSelectionDoesNotDependOnListOrder(){
        PlaybackTracks tracks=new PlaybackTracks("{\"sources\":[{\"url\":\"https://media.example/4k.m3u8\",\"label\":\"2160p\"},{\"url\":\"https://media.example/sd.m3u8\",\"label\":\"720p\"},{\"url\":\"https://media.example/hd.m3u8\",\"label\":\"1080p\"}]}");
        assertNull(PlaybackQuality.source(tracks.sources,PlaybackQuality.AUTO));
        assertEquals("720p",PlaybackQuality.source(tracks.sources,720).label);
        assertEquals("1080p",PlaybackQuality.source(tracks.sources,1080).label);
        assertEquals("2160p",PlaybackQuality.source(tracks.sources,PlaybackQuality.HIGHEST).label);
        assertEquals("720p",PlaybackQuality.source(tracks.sources,480).label);
    }
}
