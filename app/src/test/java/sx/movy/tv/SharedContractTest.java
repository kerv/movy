package sx.movy.tv;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SharedContractTest {
    @Test public void bothPlatformsUseTheSameHomeFixture()throws Exception{
        try(InputStream input=getClass().getClassLoader().getResourceAsStream("catalog.json")){
            assertNotNull(input);JSONObject data=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            java.lang.reflect.Method rows=CatalogClient.class.getDeclaredMethod("rowsFrom",JSONObject.class);rows.setAccessible(true);
            List<CatalogClient.Row> result=(List<CatalogClient.Row>)rows.invoke(null,data);assertEquals(2,result.size());assertEquals(2,result.get(0).titles.size());assertEquals("Popular on Netflix",result.get(1).name);assertEquals("/movie/603",result.get(0).titles.get(0).slug);
            assertEquals("https://www.movy.sx/img/w342/poster.jpg/q50.webp",result.get(0).titles.get(0).poster);assertEquals("https://www.movy.sx/img/w780/backdrop.jpg/q65.webp",result.get(0).titles.get(0).backdrop);
        }
    }
    @Test public void sharedArtworkContractResolvesNewAndLegacyUrls()throws Exception{
        try(InputStream input=getClass().getClassLoader().getResourceAsStream("artwork-urls.json")){
            assertNotNull(input);org.json.JSONArray cases=new org.json.JSONArray(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            for(int i=0;i<cases.length();i++){JSONObject item=cases.getJSONObject(i);String result=ArtworkUrls.normalize(item.isNull("input")?null:item.getString("input"));assertEquals(item.getString("name"),item.getString("android"),result);assertEquals("Idempotent: "+item.getString("name"),result,ArtworkUrls.normalize(result));}
        }
    }
    @Test public void rawRelativeArtworkUsesTheNormalizedBitmapCache()throws Exception{
        String relative="/img/w342/loader-fixture.jpg/q50.webp",absolute=MovyConfig.SITE+relative;
        java.lang.reflect.Field field=CatalogClient.class.getDeclaredField("IMAGES");field.setAccessible(true);
        android.util.LruCache<String,android.graphics.Bitmap> cache=(android.util.LruCache<String,android.graphics.Bitmap>)field.get(null);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);cache.put(absolute,bitmap);
        try{android.graphics.Bitmap[] loaded={null};CatalogClient.image(relative,(value,error)->{assertNull(error);loaded[0]=value;});assertSame("Raw detail / saved artwork must normalize before cache lookup",bitmap,loaded[0]);}
        finally{cache.remove(absolute);}
    }
    @Test public void generatedProvidersMatchTheSharedContract(){assertEquals(7,CatalogClient.PROVIDERS.length);assertEquals("Disney+",CatalogClient.PROVIDERS[3].name);assertEquals(337,CatalogClient.PROVIDERS[3].watchId);assertEquals(2739,CatalogClient.PROVIDERS[3].network);assertEquals("https://backend.movy.sx/v1",MovyConfig.ACCOUNT);}
}
