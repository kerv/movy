package sx.movy.tv;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** The same caption search service used by Movy's subtitle UI. It is called
 * only during playback, and does not depend on browser message support. */
final class SubtitleLookup {
    private static final ExecutorService IO=Executors.newFixedThreadPool(2);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    static String endpoint(String key){
        if(key==null||!key.matches("^/(movie|tv)/[0-9]+(?:/[0-9]+/[0-9]+)?$"))throw new IllegalArgumentException("This title cannot be searched for captions.");
        String[] parts=key.split("/");String url="https://subtitles.vidy.st/search?id="+parts[2];
        if(parts[1].equals("tv"))url+="&season="+(parts.length>=5?parts[3]:"1")+"&episode="+(parts.length>=5?parts[4]:"1");
        return url;
    }
    static void fetch(String key,CatalogClient.Result<JSONArray> callback){
        IO.execute(()->{HttpURLConnection connection=null;try{
            connection=(HttpURLConnection)new URL(endpoint(key)).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setRequestProperty("User-Agent","MovyTV/1.15");connection.setRequestProperty("Referer",CatalogClient.SITE+"/");
            StringBuilder body=new StringBuilder();try(BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"))){String line;while((line=reader.readLine())!=null)body.append(line);}
            JSONArray result=normalize(new JSONArray(body.toString()));MAIN.post(()->callback.accept(result,null));
        }catch(Exception e){MAIN.post(()->callback.accept(null,e));}finally{if(connection!=null)connection.disconnect();}});
    }
    static JSONArray normalize(JSONArray response)throws Exception{
        JSONArray result=new JSONArray();Set<String> urls=new HashSet<>();Map<String,Integer> counts=new HashMap<>();
        for(int i=0;i<response.length();i++){JSONObject item=response.optJSONObject(i);if(item==null)continue;String url=item.optString("url"),format=item.optString("format").toLowerCase(Locale.ROOT);if(!PlaybackTracks.https(url)||(!format.equals("srt")&&!format.equals("vtt"))||!urls.add(url))continue;
            String lang=PlaybackTracks.languageCode(item.optString("language"));int count=counts.containsKey(lang)?counts.get(lang):0;
            if(count>=3)continue;counts.put(lang,count+1);
            String label=PlaybackTracks.languageLabel(item.optString("display"),lang,"Subtitles");if(item.optBoolean("isHearingImpaired"))label+=" · CC";
            String release=item.optString("release");if(!release.isEmpty())label+=" · "+(release.length()>45?release.substring(0,45)+"…":release);
            JSONObject track=new JSONObject();track.put("url",url);track.put("lang",lang);track.put("label",label);track.put("format",format);result.put(track);
            if(result.length()>=60)break;
        }
        return result;
    }
}
