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
        }
    }
    @Test public void generatedProvidersMatchTheSharedContract(){assertEquals(7,CatalogClient.PROVIDERS.length);assertEquals("Disney+",CatalogClient.PROVIDERS[3].name);assertEquals(337,CatalogClient.PROVIDERS[3].watchId);assertEquals(2739,CatalogClient.PROVIDERS[3].network);assertEquals("https://backend.movy.sx/v1",MovyConfig.ACCOUNT);}
}
