import java.net.*;
public class Grid {
    static String show(URL u) {
        return u + " | p=" + u.getProtocol() + " a=" + u.getAuthority() + " u=" + u.getUserInfo() + " h=[" + u.getHost() + "] port=" + u.getPort()
            + " dp=" + u.getDefaultPort() + " path=" + u.getPath() + " file=" + u.getFile() + " q=" + u.getQuery() + " r=" + u.getRef();
    }
    public static void main(String[] a) throws Exception {
        String[] specs = { "http://example.com", "http://example.com/", "HTTP://Example.COM:8080/a/b?x=1#frag", "https://user:pw@host:443/p",
            "file:/tmp/x.txt", "file:///tmp/x.txt", "file://host/tmp/x", "file:relative/x", "file:x y.txt", "jar:file:/a.jar!/b/c.txt",
            "jar:file:/a.jar", "jar:http://h/a.jar!/", "ftp://h/x;type=a", "mailto:a@b.c", "abc", "", "xyz:foo", "http:", "http://", "http://h:port/",
            "http://h:-1/", "http://h:70000/", "http://[::1]:80/x", "http://h/a b", "http://h/é", "http://h?q", "http://h#r", "http://h/p?q#r?s",
            " http://h/ ", "url:http://h/x", "<URL:http://h/x>", "http:/x", "http:x", "file:", "jrt:/java.base", "Http://h", "h ttp://x", "1http://x", "a+b.c-d://x",
            "http://h/%zz", "http://a@b@c/", "http://h:/x", "file:/c:/x", "jar:file:/a.jar!/b!/c", "#r", "?q", "/x", "//h/x" };
        for (String s : specs) {
            try { System.out.println("[" + s + "] " + show(new URL(s))); }
            catch (Exception e) { System.out.println("[" + s + "] " + e); }
        }
        for (String s : specs) {
            try { URL u = new URL(s); System.out.println("URI [" + s + "] " + u.toURI()); }
            catch (Exception e) { System.out.println("URI [" + s + "] " + e); }
        }
        String[] uris = { "http://h/x", "file:/x", "rel/x", "mailto:a@b", "xyz:/x", "jar:file:/a.jar!/x", "http://h:80/x?q#f", "file:///x", "http://h/a%20b", "urn:isbn:1" };
        for (String s : uris) {
            try { System.out.println("toURL [" + s + "] " + show(new URI(s).toURL())); }
            catch (Exception e) { System.out.println("toURL [" + s + "] " + e); }
        }
        for (String s : uris) {
            try { System.out.println("of [" + s + "] " + show(URL.of(new URI(s), null))); }
            catch (Exception e) { System.out.println("of [" + s + "] " + e); }
        }
    }
}
