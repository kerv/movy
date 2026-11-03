package sx.movy.tv;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class UpdatePolicyTest {
    @Test public void onlyExactHttpsPublisherApkIsAllowed(){
        assertTrue(UpdatePolicy.allowedUrl("https://kerv.github.io/movy/movy.apk"));
        for(String url:new String[]{"http://kerv.github.io/movy/movy.apk","https://kerv.github.io.evil.example/movy/movy.apk",
                "https://kerv.github.io@evil.example/movy/movy.apk","https://kerv.github.io:444/movy/movy.apk",
                "https://kerv.github.io/other.apk","https://kerv.github.io/movy/movy.apk?redirect=evil","file:///movy.apk"})
            assertFalse(url,UpdatePolicy.allowedUrl(url));
        assertFalse(UpdatePolicy.allowedUrl(null));
        assertTrue(UpdatePolicy.validHash(new String(new char[64]).replace('\0','a')));
        assertFalse(UpdatePolicy.validHash(""));assertFalse(UpdatePolicy.validHash("not-a-checksum"));
    }
    @SuppressWarnings("deprecation") private PackageInfo apk(String name,int version,String signature){
        PackageInfo info=new PackageInfo();info.packageName=name;info.setLongVersionCode(version);
        info.signatures=signature==null?null:new Signature[]{new Signature(signature)};return info;
    }
    @Test public void requireNewerSamePackageAndSameSigner(){
        PackageInfo installed=apk("sx.movy.tv",23,"abcd");
        assertTrue(UpdatePolicy.matchesInstalled(installed,apk("sx.movy.tv",24,"abcd")));
        assertFalse(UpdatePolicy.matchesInstalled(installed,apk("other.app",24,"abcd")));
        assertFalse(UpdatePolicy.matchesInstalled(installed,apk("sx.movy.tv",23,"abcd")));
        assertFalse(UpdatePolicy.matchesInstalled(installed,apk("sx.movy.tv",22,"abcd")));
        assertFalse(UpdatePolicy.matchesInstalled(installed,apk("sx.movy.tv",24,"1234")));
        assertFalse(UpdatePolicy.matchesInstalled(installed,apk("sx.movy.tv",24,null)));
        assertFalse(UpdatePolicy.matchesInstalled(installed,null));
    }
}
