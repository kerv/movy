package sx.movy.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class NextEpisodeFinderTest {
    private CatalogClient.Title title(String slug)throws Exception{
        return new CatalogClient.Title(new JSONObject().put("id",10).put("slug",slug));
    }
    @Test public void nonContiguousEpisodesUseTheirMetadataNumber()throws Exception{
        JSONArray items=new JSONArray("[{\"episode_number\":2,\"air_date\":\"2020-01-01\"},{\"episode_number\":4,\"air_date\":\"2020-01-02\"}]");
        boolean[] called={false};
        NextEpisodeFinder.find(title("/tv/10/1/2"),(id,season,callback)->callback.accept(items,null),(next,error)->{
            called[0]=true;assertNull(error);assertEquals("/tv/10/1/4",next.key);assertTrue(next.released());
        });
        assertTrue(called[0]);
    }
    @Test public void nextSeasonUsesActualFirstEpisodeAndFutureDateStaysUnreleased()throws Exception{
        boolean[] called={false};
        NextEpisodeFinder.find(title("/tv/10/1/10"),(id,season,callback)->{
            try{callback.accept(season==1?new JSONArray():new JSONArray("[{\"episode_number\":3,\"air_date\":\"2099-01-01\"}]"),null);}
            catch(Exception error){throw new AssertionError(error);}
        },(next,error)->{
            called[0]=true;assertNull(error);assertEquals("/tv/10/2/3",next.key);assertFalse(next.released());
        });
        assertTrue(called[0]);
    }
    @Test public void failedLookupReportsErrorWithoutGuessingAnEpisode()throws Exception{
        Exception failure=new Exception("offline");boolean[] called={false};
        NextEpisodeFinder.find(title("/tv/10/1/1"),(id,season,callback)->callback.accept(null,failure),(next,error)->{
            called[0]=true;assertNull(next);assertSame(failure,error);
        });
        assertTrue(called[0]);assertFalse(new NextEpisodeFinder.Next("/tv/10/2/3","").released());
    }
}
