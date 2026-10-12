package sx.movy.tv;

import androidx.media3.common.C;
import androidx.media3.exoplayer.Renderer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Relative presentation timing without modifying audio samples or the master clock. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackTiming {
    private volatile int audioMs, subtitleMs;
    int audioMs(){return audioMs;}
    int subtitleMs(){return subtitleMs;}
    void setAudioMs(int value){audioMs=Math.max(-2000,Math.min(2000,value));}
    void setSubtitleMs(int value){subtitleMs=Math.max(-10000,Math.min(10000,value));}
    long position(int type,long positionUs){
        // Audio is ExoPlayer's master clock. Advance the picture instead of delaying
        // that clock, which would cancel the correction. Captions follow the picture
        // plus their independent delay. Positive values mean audio/captions LATER.
        if(type==C.TRACK_TYPE_VIDEO)return positionUs+audioMs*1000L;
        if(type==C.TRACK_TYPE_TEXT)return positionUs+(audioMs-subtitleMs)*1000L;
        return positionUs;
    }
    Renderer wrap(Renderer delegate){
        int type=delegate.getTrackType();
        if(type!=C.TRACK_TYPE_VIDEO&&type!=C.TRACK_TYPE_TEXT)return delegate;
        // Forward the complete version-pinned Renderer interface, including codec,
        // surface, clock and lifecycle messages. Only presentation positions change;
        // stream timestamps, period offsets and elapsed wall time must stay intact.
        return (Renderer)Proxy.newProxyInstance(Renderer.class.getClassLoader(),new Class<?>[]{Renderer.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("render")||name.equals("resetPosition"))args[0]=position(type,(Long)args[0]);
            else if(name.equals("enable"))args[3]=position(type,(Long)args[3]);
            try{return method.invoke(delegate,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
    }
}
