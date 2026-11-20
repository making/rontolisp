import java.net.*;
public class Grid2 {
    static String show(URL u) {
        return u + " | p=" + u.getProtocol() + " a=" + u.getAuthority() + " u=" + u.getUserInfo() + " h=[" + u.getHost() + "] port=" + u.getPort()
            + " dp=" + u.getDefaultPort() + " path=" + u.getPath() + " file=" + u.getFile() + " q=" + u.getQuery() + " r=" + u.getRef();
    }
    public static void main(String[] a) throws Exception {
        String[] specs = { "jar:file:/a.jar!/x#r", "jar:file:/a.jar!/x?q", "jmod:/x", "ldap://h/x", "gopher://h/", "netdoc:/x", "data:text,x", "ws://h/", "about:blank", "javascript:x",
            "http://h:-2/", "http://h:+5/", "http://h:08/", "http://h:99999999999/", "http://[::1/", "http://::1/", "http://u@h:1/", "http://@h/", "http://u@/", "http://h:1:2/",
            "HTTP://H/", "http://h//x", "http:///x", "http://h\\x", "\thttp://h/\n", "URL:http://h/", "url:x", "http://h/ä?ü#ö", "http://h/a#b#c", "http://h/a?b?c",
            "http://h/a?", "http://h/a#", "mailto:", "file:#x", "file:?x", "file://", "file:////x", "http://h/.././x", "http://H:80", "jar:!/", "jar:x!/y", "jar:!/x"};
        for (String s : specs) {
            try { System.out.println("[" + s + "] " + show(new URL(s))); }
            catch (Exception e) { System.out.println("[" + s + "] " + e); }
        }
        String[][] ctor = { {"http","h","80","/x"}, {"HTTP","h","-1","x"}, {"http","h","-2","/x"}, {"xyz","h","1","/x"}, {"file","","-1","/x"}, {"http","::1","8","/x"}, {"http","h","1","/x?q#r"}, {"jar","","-1","file:/a.jar"}, {"http", null, "-1", "/x"} };
        for (String[] c : ctor) {
            try { System.out.println("ctor " + String.join(",", java.util.Arrays.asList(c).toString()) + " " + show(new URL(c[0], c[1], Integer.parseInt(c[2]), c[3]))); }
            catch (Exception e) { System.out.println("ctor " + java.util.Arrays.asList(c) + " " + e); }
            try { System.out.println("ctor3 " + java.util.Arrays.asList(c) + " " + show(new URL(c[0], c[1], c[3]))); }
            catch (Exception e) { System.out.println("ctor3 " + java.util.Arrays.asList(c) + " " + e); }
        }
        String[][] eq = { {"http://h/x", "http://h:80/x"}, {"http://H/x", "http://h/x"}, {"http://h/x#a", "http://h/x#b"}, {"http://h/x", "HTTP://h/x"}, {"file:/x", "file:///x"}, {"http://h/x?a", "http://h/x?b"}, {"http://u@h/x", "http://h/x"}, {"http://h/x", "https://h/x"}, {"http://h/X", "http://h/x"}};
        for (String[] e : eq) {
            URL x = new URL(e[0]), y = new URL(e[1]);
            System.out.println("eq " + e[0] + " " + e[1] + " " + x.equals(y) + " " + x.sameFile(y) + " " + (x.hashCode() == y.hashCode()));
        }
        try { new URL((String) null); } catch (Exception e) { System.out.println("null " + e); }
        try { URL.of(null, null); } catch (Exception e) { System.out.println("ofnull " + e); }
        try { new URL("http://h/").openStream(); } catch (Exception e) { System.out.println("open " + e); }
        try { new URL("file:/nonexistent/x").openStream(); } catch (Exception e) { System.out.println("open " + e); }
        try { new URL("mailto:x@y").openStream(); } catch (Exception e) { System.out.println("open " + e); }
    }
}
