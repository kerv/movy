package sx.movy.tv;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only episode metadata from Movy's catalogue, not assumed numbering. */
final class NextEpisodeFinder {
    static final class Next {final String key,date;Next(String key,String date){this.key=key;this.date=date;}}
    static JSONObject following(JSONArray episodes,int after){JSONObject best=null;if(episodes!=null)for(int i=0;i<episodes.length();i++){JSONObject item=episodes.optJSONObject(i);if(item!=null&&item.optInt("episode_number")>after&&(best==null||item.optInt("episode_number")<best.optInt("episode_number")))best=item;}return best;}
    static boolean released(JSONObject episode,String today){String date=episode==null?"":episode.optString("air_date");return date.matches("\\d{4}-\\d{2}-\\d{2}")&&date.compareTo(today)<=0;}
    static Next candidate(CatalogClient.Title title,int season,JSONObject episode){if(episode==null||!episode.optString("air_date").matches("\\d{4}-\\d{2}-\\d{2}"))return null;return new Next("/tv/"+title.id+"/"+season+"/"+episode.optInt("episode_number"),episode.optString("air_date"));}
    static void find(CatalogClient.Title completed,CatalogClient.Result<Next> callback){
        String[] parts=completed.slug.split("/");try{if(!completed.isSeries()||parts.length!=5){callback.accept(null,null);return;}int season=Integer.parseInt(parts[3]),episode=Integer.parseInt(parts[4]);
            CatalogClient.episodes(completed.id,season,(items,error)->{if(error!=null){callback.accept(null,error);return;}JSONObject next=following(items,episode);if(next!=null){callback.accept(candidate(completed,season,next),null);return;}
                CatalogClient.episodes(completed.id,season+1,(newSeason,nextError)->{if(nextError!=null){callback.accept(null,nextError);return;}callback.accept(candidate(completed,season+1,following(newSeason,0)),null);});
            });
        }catch(Exception error){callback.accept(null,error);}
    }
}
