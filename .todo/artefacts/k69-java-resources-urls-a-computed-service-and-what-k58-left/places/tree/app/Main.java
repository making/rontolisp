package app;
import java.net.*;
import java.io.*;
import java.util.*;
public class Main {
    static String show(URL u) throws Exception {
        if (u == null) return "null";
        String text;
        try (InputStream in = u.openStream()) { text = new String(in.readAllBytes()).strip(); } catch (Exception e) { text = e.toString(); }
        return u + " proto=" + u.getProtocol() + " host=[" + u.getHost() + "] port=" + u.getPort() + " path=" + u.getPath()
            + " file=" + u.getFile() + " auth=" + u.getAuthority() + " ref=" + u.getRef() + " query=" + u.getQuery()
            + " ext=" + u.toExternalForm().equals(u.toString()) + " uri=" + u.toURI() + " text=" + text;
    }
    public static void main(String[] a) throws Exception {
        ClassLoader l = Main.class.getClassLoader();
        System.out.println(l + " parent=" + l.getParent() + " pp=" + (l.getParent() == null ? "-" : String.valueOf(l.getParent().getParent())));
        System.out.println("sys=" + ClassLoader.getSystemClassLoader() + " parent=" + ClassLoader.getSystemClassLoader().getParent() + " pp=" + ClassLoader.getSystemClassLoader().getParent().getParent());
        System.out.println("platform=" + ClassLoader.getPlatformClassLoader());
        for (String n : new String[]{"data.txt", "/top.txt", "sub/deep.txt", "libonly.txt", "jaronly.txt", "/both.txt", "missing", "", "sub", "sub/", "/"}) {
            System.out.println("C " + n + " -> " + show(Main.class.getResource(n)));
        }
        for (String n : new String[]{"app/data.txt", "top.txt", "both.txt", "app/libonly.txt", "app/jaronly.txt", "", "app", "app/", "lib/Lib.class"}) {
            System.out.println("L " + n + " -> " + show(l.getResource(n)));
            System.out.println("S " + n + " -> " + show(ClassLoader.getSystemResource(n)));
            System.out.println("R " + n + " -> " + Collections.list(l.getResources(n)));
            System.out.println("SR " + n + " -> " + Collections.list(ClassLoader.getSystemResources(n)));
        }
        System.out.println("lib " + show(lib.Lib.find("/app/data.txt")));
        System.out.println("lib2 " + show(lib.Lib.find("Lib.class")));
    }
}
