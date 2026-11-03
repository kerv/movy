package sx.movy.tv;

import java.net.URI;

/** Resolve catalog, details and saved artwork using the same origin as Movy's page. */
final class ArtworkUrls {
    private static final URI BASE=URI.create(MovyConfig.SITE+"/");
    private ArtworkUrls() {}
    static String normalize(String address){
        if(address==null)return "";
        String value=address.trim();if(value.isEmpty())return "";
        for(int i=0;i<value.length();i++)if(Character.isWhitespace(value.charAt(i))||Character.isISOControl(value.charAt(i))||value.charAt(i)=='\\')return "";
        try{
            URI resolved=BASE.resolve(new URI(value)).normalize();
            if(!"https".equalsIgnoreCase(resolved.getScheme())||resolved.getHost()==null||resolved.getRawUserInfo()!=null)return "";
            // Keep the actual route/format suffix. /q50.webp is a server route,
            // not a filename extension we can safely rewrite to /q50.jpg.
            return resolved.toASCIIString();
        }catch(IllegalArgumentException|java.net.URISyntaxException invalid){return "";}
    }
}
