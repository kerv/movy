package sx.movy.tv;

import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class LibraryBehaviorTest {
    private CatalogClient.Title title(String key)throws Exception{return new CatalogClient.Title(new JSONObject().put("id",Long.parseLong(key.split("/")[2])).put("slug",key).put("title","Fixture"));}
    @Test public void movieAndSeriesFiltersUseCorrectDatesSortAndProvider(){
        String movie=CatalogClient.libraryUrl("movie",CatalogClient.PROVIDERS[3],28,CatalogClient.Sort.RELEASE_DESC,3,"2026-10-12");
        assertTrue(movie.contains("primary_release_date.desc"));assertTrue(movie.contains("primary_release_date.lte=2026-10-12"));assertTrue(movie.contains("with_watch_providers=337&watch_region=US"));assertTrue(movie.contains("with_genres=28"));assertTrue(movie.contains("page=3"));
        String series=CatalogClient.libraryUrl("tv",null,10759,CatalogClient.Sort.TITLE_ASC,2,"2026-10-12");assertTrue(series.contains("original_name.asc"));assertTrue(series.contains("first_air_date.lte=2026-10-12"));assertTrue(series.contains("with_genres=10759"));assertFalse(series.contains("with_networks"));
        assertTrue(CatalogClient.libraryUrl("tv",CatalogClient.PROVIDERS[2],0,CatalogClient.Sort.RATING,1,"2026-10-12").contains("vote_count.gte=100"));
    }
    @Test public void dismissalKeepsHistoryAndIsScopedToAccountProfile()throws Exception{
        PlaybackStore store=new PlaybackStore(Robolectric.buildActivity(NativeActivity.class).get());CatalogClient.Title title=title("/movie/603");
        store.progress(title.slug,title.title,"",120000,7200000);assertEquals(1,store.titles("resume:").size());store.dismiss(title,"account:one");
        assertTrue(store.titles("resume:").isEmpty());assertTrue(store.dismissed(title,"account:one"));assertFalse(store.dismissed(title,"account:two"));assertEquals(1,store.titles("history:").size());store.started(title.slug);store.progress(title.slug,title.title,"",150000,7200000);assertEquals(1,store.titles("resume:").size());
    }
    @Test public void completedSeriesCreatesConfirmedNextAndNewPlaybackReplacesIt()throws Exception{
        PlaybackStore store=new PlaybackStore(Robolectric.buildActivity(NativeActivity.class).get());CatalogClient.Title completed=title("/tv/1399/1/10");
        assertTrue(store.progress(completed.slug,completed.title,"",3599000,3600000));assertFalse(store.progress(completed.slug,completed.title,"",3600000,3600000));assertTrue(store.titles("resume:").isEmpty());assertEquals(1,store.titles("pending_next:").size());
        store.queueNext(completed,new NextEpisodeFinder.Next("/tv/1399/2/1","2012-04-01"));assertEquals(1,store.titles("next:").size());assertTrue(store.titles("next:").get(0).nextEpisode);assertEquals("/tv/1399/2/1",store.titles("next:").get(0).slug);
        store.progress("/tv/1399/2/1",completed.title,"",120000,3600000);assertTrue(store.titles("next:").isEmpty());assertEquals(1,store.titles("resume:").size());assertEquals("/tv/1399/2/1",store.titles("resume:").get(0).slug);
        store.progress("/tv/1399/2/2",completed.title,"",150000,3600000);assertEquals(1,store.titles("resume:").size());assertEquals("/tv/1399/2/2",store.titles("resume:").get(0).slug);
    }
    @Test public void nextEpisodeWaitsForAirDateAndDismissedLookupCannotReaddIt()throws Exception{
        PlaybackStore store=new PlaybackStore(Robolectric.buildActivity(NativeActivity.class).get());CatalogClient.Title completed=title("/tv/10/1/1");store.progress(completed.slug,completed.title,"",3599000,3600000);
        store.queueNext(completed,new NextEpisodeFinder.Next("/tv/10/1/2","2099-01-01"));assertTrue(store.titles("next:").isEmpty());
        store.progress("/tv/10/1/2",completed.title,"",3599000,3600000);CatalogClient.Title second=title("/tv/10/1/2");store.dismiss(second,"account:one");store.queueNext(second,new NextEpisodeFinder.Next("/tv/10/1/3","2020-01-01"));assertTrue(store.titles("next:").isEmpty());
    }
    @Test public void recentActivityIsBoundedWithoutLimitingMyList()throws Exception{
        PlaybackStore store=new PlaybackStore(Robolectric.buildActivity(NativeActivity.class).get());for(int i=1;i<=45;i++){CatalogClient.Title title=title("/movie/"+i);store.progress(title.slug,title.title,"",120000,7200000);store.toggle(title);}assertEquals(PlaybackStore.RECENT_LIMIT,store.titles("resume:").size());assertEquals(PlaybackStore.RECENT_LIMIT,store.titles("history:").size());assertEquals(45,store.titles("saved:").size());
    }
    @Test public void episodeFinderUsesActualNumbersAndDateNotArrayLength()throws Exception{
        JSONArray episodes=new JSONArray("[{\"episode_number\":5,\"air_date\":\"2020-01-01\"},{\"episode_number\":2,\"air_date\":\"2020-01-01\"},{\"episode_number\":3,\"air_date\":\"2099-01-01\"}]");JSONObject next=NextEpisodeFinder.following(episodes,2);assertEquals(3,next.getInt("episode_number"));assertFalse(NextEpisodeFinder.released(next,"2026-10-12"));assertNull(NextEpisodeFinder.following(episodes,5));assertEquals("/tv/10/2/3",NextEpisodeFinder.candidate(title("/tv/10/1/10"),2,next).key);
    }
    @Test public void exitGuardNeedsTwoPressesAndExpiresOrResets(){PlaybackExitGuard guard=new PlaybackExitGuard();assertFalse(guard.press(0));assertTrue(guard.press(1000));assertFalse(guard.press(2000));assertFalse(guard.press(5001));guard.reset();assertFalse(guard.press(5100));}
    @Test public void playerBackHidesControlsWithoutExitAndIgnoresHeldRepeats(){
        PlayerActivity player=Robolectric.buildActivity(PlayerActivity.class).get();player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));assertFalse(player.isFinishing());
        player.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,3));assertFalse(player.isFinishing());player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));assertFalse(player.isFinishing());player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));assertFalse(player.isFinishing());
        player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));assertTrue(player.isFinishing());
    }
}
