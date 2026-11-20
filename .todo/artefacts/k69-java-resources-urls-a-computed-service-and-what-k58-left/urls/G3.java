import java.net.*;
public class G3 {
    public static void main(String[] a) throws Exception {
        for (String s : new String[]{"jar:!/", "jar:x!/y", "jar:xyz:/a!/y", "jar:file:/a.jar!/", "http://h /x", "http://h<x/", "http://h%41/", "http://h_x/", "http://h:1 /", "file://h:5/x", "http://h:/", "http://h:+/", "http://h:-/" , "http://[::1]x/", "http://[::1]:/", "http://[::1]:z/", "http://u:p@[::1]:9/"}) {
            try { URL u = new URL(s); System.out.println("[" + s + "] ok " + u + " h=" + u.getHost() + " p=" + u.getPort() + " a=" + u.getAuthority()); }
            catch (Exception e) { System.out.println("[" + s + "] <" + e.getMessage() + "> " + e.getClass().getName() + " cause=" + e.getCause()); }
        }
    }
}
