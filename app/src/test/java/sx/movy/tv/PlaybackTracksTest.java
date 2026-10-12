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
    @Test public void nativeSourcesMenuIncludesServersAndRefresh() throws Exception {
        PlayerActivity activity=org.robolectric.Robolectric.buildActivity(PlayerActivity.class).get();
        java.lang.reflect.Field field=PlayerActivity.class.getDeclaredField("available");field.setAccessible(true);field.set(activity,new PlaybackTracks("{\"server\":\"Miami\",\"servers\":[{\"name\":\"Miami\"},{\"name\":\"Boise\"}]}"));
        java.lang.reflect.Method method=PlayerActivity.class.getDeclaredMethod("sources");method.setAccessible(true);method.invoke(activity);
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(3,dialog.getListView().getAdapter().getCount());assertEquals("✓ Miami",dialog.getListView().getAdapter().getItem(0));assertEquals("Boise",dialog.getListView().getAdapter().getItem(1));assertEquals("Refresh current source",dialog.getListView().getAdapter().getItem(2));dialog.dismiss();
    }
    @Test public void cancelledSourceChangeKeepsPositionAndRestoresPausedOrPlayingState() throws Exception {
        PlayerActivity activity=org.robolectric.Robolectric.buildActivity(PlayerActivity.class).get();androidx.media3.exoplayer.ExoPlayer player=new androidx.media3.exoplayer.ExoPlayer.Builder(activity).build();
        try {
            for(String name:new String[]{"player","root","view"}){java.lang.reflect.Field field=PlayerActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(activity,name.equals("player")?player:name.equals("root")?new android.widget.FrameLayout(activity):new androidx.media3.ui.PlayerView(activity));}
            player.seekTo(91234);java.lang.reflect.Method show=PlayerActivity.class.getDeclaredMethod("showSourceLoading",String.class);show.setAccessible(true);show.invoke(activity,"Boise");
            java.lang.reflect.Field flag=PlayerActivity.class.getDeclaredField("sourceWasPlaying");flag.setAccessible(true);flag.set(activity,true);
            java.lang.reflect.Method cancel=PlayerActivity.class.getDeclaredMethod("cancelSource",boolean.class);cancel.setAccessible(true);cancel.invoke(activity,true);assertTrue(player.getPlayWhenReady());assertEquals(91234,player.getCurrentPosition());
            java.lang.reflect.Field overlay=PlayerActivity.class.getDeclaredField("sourceLoading");overlay.setAccessible(true);assertEquals(android.view.View.GONE,((android.view.View)overlay.get(activity)).getVisibility());
            player.pause();flag.set(activity,false);show.invoke(activity,"Miami");cancel.invoke(activity,true);assertFalse(player.getPlayWhenReady());assertEquals(91234,player.getCurrentPosition());
        } finally {player.release();}
    }
    @Test public void sourceServersAreSeparateFromQualityAndDeduplicated(){
        PlaybackTracks tracks=new PlaybackTracks("{\"server\":\"Miami\",\"servers\":[{\"name\":\"Miami\",\"label\":\"Miami · US\"},{\"name\":\"Boise\"},{\"name\":\"Miami\"},{\"name\":\"\"}],\"sources\":[{\"url\":\"https://media.example/4k.m3u8\",\"label\":\"2160p\"}]}");
        assertEquals("Miami",tracks.server);assertEquals(2,tracks.servers.size());assertEquals("Boise",tracks.servers.get(1).label);assertEquals(1,tracks.sources.size());assertEquals("2160p",tracks.sources.get(0).label);
    }
    @Test public void lateSubtitleSelectionChoosesExactEditionRatherThanSameLanguage() throws Exception {
        PlayerActivity activity=org.robolectric.Robolectric.buildActivity(PlayerActivity.class).get();
        androidx.media3.exoplayer.ExoPlayer player=new androidx.media3.exoplayer.ExoPlayer.Builder(activity).build();
        try {
            java.lang.reflect.Field field=PlayerActivity.class.getDeclaredField("player");field.setAccessible(true);field.set(activity,player);
            field=PlayerActivity.class.getDeclaredField("pendingSubtitleId");field.setAccessible(true);field.set(activity,"movy-sub-1");
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build());
            TrackGroup group=new TrackGroup("captions",new Format.Builder().setId("movy-sub-0").setLanguage("en").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),new Format.Builder().setId("movy-sub-1").setLanguage("en").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build());
            Tracks tracks=new Tracks(java.util.Collections.singletonList(new Tracks.Group(group,false,new int[]{C.FORMAT_HANDLED,C.FORMAT_HANDLED},new boolean[]{false,false})));
            java.lang.reflect.Method method=PlayerActivity.class.getDeclaredMethod("applyPendingSubtitle",Tracks.class);method.setAccessible(true);method.invoke(activity,tracks);
            assertNull(field.get(activity));assertFalse(player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            assertEquals(java.util.Collections.singletonList(1),player.getTrackSelectionParameters().overrides.get(group).trackIndices);
        } finally {player.release();}
    }
    @Test public void subtitleSearchUsesCorrectEpisodeAndHonorsDownloadFormat() throws Exception {
        assertEquals("https://subtitles.vidy.st/search?id=603",SubtitleLookup.endpoint("/movie/603"));
        assertEquals("https://subtitles.vidy.st/search?id=1399&season=2&episode=3",SubtitleLookup.endpoint("/tv/1399/2/3"));
        org.json.JSONArray response=new org.json.JSONArray("[{\"url\":\"https://captions.example/download\",\"format\":\"srt\",\"language\":\"eng\",\"display\":\"English\",\"isHearingImpaired\":true},{\"url\":\"javascript:bad\",\"format\":\"srt\"},{\"url\":\"https://captions.example/download\",\"format\":\"srt\"}]");
        org.json.JSONArray files=SubtitleLookup.normalize(response);assertEquals(1,files.length());
        PlaybackTracks tracks=new PlaybackTracks(new org.json.JSONObject().put("subtitles",files).toString());
        assertEquals(MimeTypes.APPLICATION_SUBRIP,tracks.subtitles.get(0).mimeType);assertEquals("en",tracks.subtitles.get(0).language);assertTrue(tracks.subtitles.get(0).label.contains("CC"));
    }
    @Test public void sidecarCaptionsAreMergedWithVideoAndLanguagesAreReadable(){
        PlaybackTracks tracks=new PlaybackTracks("{\"subtitles\":[{\"url\":\"https://media.example/eng.vtt\",\"lang\":\"eng\",\"label\":\"eng\"},{\"url\":\"javascript:bad\"}],\"sources\":[{\"url\":\"https://media.example/4k.m3u8\",\"label\":\"2160p\"}]}");
        assertEquals(1,tracks.subtitles.size());assertEquals("English",tracks.subtitles.get(0).label);assertEquals("en",tracks.subtitles.get(0).language);
        MediaItem item=new MediaItem.Builder().setUri("https://media.example/video.m3u8").setMimeType(MimeTypes.APPLICATION_M3U8).setSubtitleConfigurations(tracks.subtitles).build();
        assertTrue(new DefaultMediaSourceFactory(new DefaultHttpDataSource.Factory()).createMediaSource(item) instanceof MergingMediaSource);
        assertEquals("Original audio",PlaybackTracks.languageLabel("```","und","Original audio"));
    }
}
