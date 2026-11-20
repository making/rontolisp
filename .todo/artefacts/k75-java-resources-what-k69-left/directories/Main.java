package app;

import java.io.*;
import java.net.*;
import java.util.*;

public class Main {
    static String show(InputStream in) throws IOException {
        if (in == null) return "null";
        return "[" + new String(in.readAllBytes()).replace("\n", "|") + "]";
    }
    static String url(URL u) throws IOException {
        if (u == null) return "null";
        String text = u.toString();
        int cut = text.indexOf("/d3/");
        return text.substring(0, text.indexOf(':') + 1) + "~" + text.substring(cut + 3) + " " + show(u.openStream());
    }
    public static void main(String[] args) throws Exception {
        ClassLoader l = Main.class.getClassLoader();
        String[] names = { "sub", "sub/", "", "/app/sub", "/app", "/", "sub/deeper", "sub/emptydir", "sub/a.txt/", "nope" };
        String prefix = args.length > 0 ? args[0] : "";
        for (String n : names) {
            System.out.println("C " + n + " -> " + url(Main.class.getResource(n)) + " " + show(Main.class.getResourceAsStream(n)));
            System.out.println("c " + n + " -> " + url(Main.class.getResource(prefix + n)) + " " + show(Main.class.getResourceAsStream(prefix + n)));
        }
        String[] lnames = { "app/sub", "app/sub/", "app", "", "lone", "lone/x/", "jaronly", "jaronly/", "nd", "app/sub/deeper", "app/sub/.", "app/sub/deeper/..", "lone/./x", "jaronly/k.txt/", "app/sub/l.txt/" };
        for (String n : lnames) {
            System.out.println("L " + n + " -> " + url(l.getResource(n)) + " " + show(l.getResourceAsStream(n)));
            System.out.println("l " + n + " -> " + url(l.getResource(prefix + n)) + " " + show(l.getResourceAsStream(prefix + n)));
            Enumeration<URL> e = l.getResources(n);
            while (e.hasMoreElements()) System.out.println("   * " + url(e.nextElement()));
            e = l.getResources(prefix + n);
            while (e.hasMoreElements()) System.out.println("   + " + url(e.nextElement()));
            System.out.println("   S " + url(ClassLoader.getSystemResource(n)) + " " + url(ClassLoader.getSystemResource(prefix + n)));
        }
    }
}
