package sx.movy.tv;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Fail closed before handing an update to Android's package installer. */
final class UpdatePolicy {
    static final long MAX_APK_BYTES=64L*1024*1024;

    static boolean allowedUrl(String address){
        try{
            URI uri=new URI(address);
            return "https".equals(uri.getScheme())&&"kerv.github.io".equals(uri.getHost())
                    &&(uri.getPort()==-1||uri.getPort()==443)&&uri.getUserInfo()==null
                    &&uri.getQuery()==null&&uri.getFragment()==null
                    &&"/movy/movy.apk".equals(uri.getRawPath());
        }catch(Exception invalid){return false;}
    }

    static boolean validHash(String hash){return hash!=null&&hash.matches("(?i)[0-9a-f]{64}");}

    @SuppressWarnings("deprecation")
    static boolean matchesInstalled(PackageInfo installed,PackageInfo update){
        if(installed==null||update==null||!installed.packageName.equals(update.packageName))return false;
        long oldVersion=Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode;
        long newVersion=Build.VERSION.SDK_INT>=28?update.getLongVersionCode():update.versionCode;
        if(newVersion<=oldVersion)return false;
        Signature[] oldSignatures=installed.signatures,newSignatures=update.signatures;
        if(oldSignatures==null||newSignatures==null||oldSignatures.length==0||newSignatures.length==0)return false;
        Set<Signature> expected=new HashSet<>(Arrays.asList(oldSignatures));
        return expected.equals(new HashSet<>(Arrays.asList(newSignatures)));
    }
}
