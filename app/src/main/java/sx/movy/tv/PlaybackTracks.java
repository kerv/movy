package sx.movy.tv;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** The browser's sidecar subtitles and alternative sources, not guessed URLs. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackTracks {
    static final class Source {final String url,label;Source(String url,String label){this.url=url;this.label=label;}}
    static final class Server {final String name,label;Server(String name,String label){this.name=name;this.label=label;}}
    final List<MediaItem.SubtitleConfiguration> subtitles=new ArrayList<>();
    final List<Source> sources=new ArrayList<>();
    final List<Server> servers=new ArrayList<>();
    String server="";
    PlaybackTracks(String raw){
        try {JSONObject data=new JSONObject(raw==null?"{}":raw);JSONArray subs=data.optJSONArray("subtitles"),media=data.optJSONArray("sources");Set<String>seen=new HashSet<>();
            if(subs!=null)for(int i=0;i<subs.length();i++){JSONObject s=subs.optJSONObject(i);if(s==null)continue;String url=s.optString("url");if(!https(url)||!seen.add(url))continue;
                String language=languageCode(s.optString("lang")),label=languageLabel(s.optString("label"),language,"Subtitles "+(i+1));
                String path=Uri.parse(url).getPath();String mime="srt".equalsIgnoreCase(s.optString("format"))||(path!=null&&path.toLowerCase(Locale.ROOT).endsWith(".srt"))?MimeTypes.APPLICATION_SUBRIP:MimeTypes.TEXT_VTT;
                subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setId("movy-sub-"+i).setLabel(label).setLanguage(language).setMimeType(mime).setRoleFlags(C.ROLE_FLAG_SUBTITLE).build());
            }
            seen.clear();if(media!=null)for(int i=0;i<media.length();i++){JSONObject s=media.optJSONObject(i);if(s==null)continue;String url=s.optString("url");if(https(url)&&seen.add(url))sources.add(new Source(url,languageLabel(s.optString("label"),"","Source "+(i+1))));}
            JSONArray advertised=data.optJSONArray("servers");seen.clear();if(advertised!=null)for(int i=0;i<Math.min(40,advertised.length());i++){JSONObject s=advertised.optJSONObject(i);if(s==null)continue;String name=s.optString("name").trim();if(name.isEmpty()||name.length()>60||!seen.add(name))continue;servers.add(new Server(name,languageLabel(s.optString("label"),"",name)));}
            server=data.optString("server");
        }catch(Exception ignored){}
    }
    static boolean https(String url){Uri uri=Uri.parse(url);return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null;}
    static String languageCode(String code){
        if(code==null)return "";String c=code.trim().toLowerCase(Locale.ROOT);Map<String,String> aliases=new HashMap<>();String[] pairs={"eng","en","fre","fr","fra","fr","ger","de","deu","de","spa","es","por","pt","cze","cs","ces","cs","dut","nl","nld","nl","rum","ro","ron","ro","chi","zh","zho","zh","slo","sk","slk","sk","gre","el","ell","el","ice","is","isl","is","nob","nb"};for(int i=0;i<pairs.length;i+=2)aliases.put(pairs[i],pairs[i+1]);return aliases.containsKey(c)?aliases.get(c):c;
    }
    static String languageLabel(String label,String language,String fallback){
        String clean=label==null?"":label.replace("`","").trim();String code=languageCode(language);
        if(!clean.isEmpty()&&!clean.equalsIgnoreCase("und")&&!clean.equalsIgnoreCase("unknown")){
            String candidate=languageCode(clean);if(candidate.matches("[a-z]{2,3}")){String name=new Locale(candidate).getDisplayLanguage(Locale.ENGLISH);if(!name.isEmpty()&&!name.equalsIgnoreCase(candidate))return name;}
            if(!clean.matches("[\\p{Punct}\\s]+"))return clean;
        }
        if(!code.isEmpty()&&!code.equals("und")){String name=new Locale(code).getDisplayLanguage(Locale.ENGLISH);if(!name.isEmpty()&&!name.equalsIgnoreCase(code))return name;}
        return fallback;
    }
}
