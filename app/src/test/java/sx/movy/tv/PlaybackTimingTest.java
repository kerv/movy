package sx.movy.tv;

import androidx.media3.common.C;
import androidx.media3.exoplayer.Renderer;
import java.lang.reflect.Proxy;
import org.junit.Test;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PlaybackTimingTest {
    @Test public void signsAndIndependentCaptionOffsetFollowAudioMaster(){
        PlaybackTiming timing=new PlaybackTiming();timing.setAudioMs(200);timing.setSubtitleMs(500);
        assertEquals(10200000,timing.position(C.TRACK_TYPE_VIDEO,10000000));
        assertEquals(9700000,timing.position(C.TRACK_TYPE_TEXT,10000000));
        assertEquals(10000000,timing.position(C.TRACK_TYPE_AUDIO,10000000));
        timing.setAudioMs(-200);timing.setSubtitleMs(-500);
        assertEquals(9800000,timing.position(C.TRACK_TYPE_VIDEO,10000000));assertEquals(10300000,timing.position(C.TRACK_TYPE_TEXT,10000000));
        timing.setAudioMs(0);timing.setSubtitleMs(0);assertEquals(10000000,timing.position(C.TRACK_TYPE_TEXT,10000000));
    }
    @Test public void excessiveValuesAreClamped(){PlaybackTiming timing=new PlaybackTiming();timing.setAudioMs(3000);timing.setSubtitleMs(-20000);assertEquals(2000,timing.audioMs());assertEquals(-10000,timing.subtitleMs());}
    @Test public void wrapperAdjustsPresentationAndSeekButNotWallTimeOrAudio()throws Exception{
        long[] seen=new long[2];Renderer fake=(Renderer)Proxy.newProxyInstance(Renderer.class.getClassLoader(),new Class[]{Renderer.class},(p,m,a)->{if(m.getName().equals("getTrackType"))return C.TRACK_TYPE_VIDEO;if(m.getName().equals("render")){seen[0]=(Long)a[0];seen[1]=(Long)a[1];}if(m.getName().equals("resetPosition"))seen[0]=(Long)a[0];return null;});
        PlaybackTiming timing=new PlaybackTiming();Renderer renderer=timing.wrap(fake);timing.setAudioMs(100);renderer.render(1000000,9000000);assertEquals(1100000,seen[0]);assertEquals(9000000,seen[1]);timing.setAudioMs(-100);renderer.resetPosition(5000000);assertEquals(4900000,seen[0]);
        Renderer audio=(Renderer)Proxy.newProxyInstance(Renderer.class.getClassLoader(),new Class[]{Renderer.class},(p,m,a)->C.TRACK_TYPE_AUDIO);assertSame(audio,timing.wrap(audio));
    }
}
