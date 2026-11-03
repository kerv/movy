package sx.movy.tv;

import android.content.Context;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Device-wide startup preference; per-stream controls remain available. */
final class PlaybackQuality {
    static final int AUTO=0,HIGHEST=-1;
    static final int[] VALUES={AUTO,720,1080,HIGHEST};
    static final String[] LABELS={"Auto (adapt to connection)","Prefer up to 720p","Prefer up to 1080p","Highest available"};
    private static final Pattern HEIGHT=Pattern.compile("(\\d{3,4})p",Pattern.CASE_INSENSITIVE);

    static int get(Context context){
        int value=context.getSharedPreferences("playback_settings",Context.MODE_PRIVATE).getInt("quality",AUTO);
        for(int allowed:VALUES)if(value==allowed)return value;
        return AUTO;
    }
    static void set(Context context,int value){
        context.getSharedPreferences("playback_settings",Context.MODE_PRIVATE).edit().putInt("quality",value).apply();
    }
    static int height(String text){Matcher match=HEIGHT.matcher(text);return match.find()?Integer.parseInt(match.group(1)):0;}
    static boolean better(int candidate,int current,int preference){
        if(preference<=0)return candidate>=current;
        if(candidate==0)return current==0;
        if(current==0)return true;
        boolean candidateFits=candidate<=preference,currentFits=current<=preference;
        if(candidateFits!=currentFits)return candidateFits;
        return candidateFits?candidate>current:candidate<current;
    }
    static PlaybackTracks.Source source(List<PlaybackTracks.Source> sources,int preference){
        if(preference==AUTO)return null;
        PlaybackTracks.Source best=null;int height=0;
        for(PlaybackTracks.Source item:sources){int next=height(item.label);if(next>0&&(best==null||better(next,height,preference))){best=item;height=next;}}
        return best;
    }
}
