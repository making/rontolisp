package app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        ResourceBundle b = ResourceBundle.getBundle("app.many", Locale.UK);
        System.out.println(Collections.list(b.getKeys()));
        System.out.println(b.keySet() + " " + b.keySet().getClass().getName());
        Map<String, Object> hm = new HashMap<>();
        for (String k : List.of("zeta", "alpha", "mid", "k10", "k2", "k33", "b", "x")) hm.put(k, k);
        System.out.println("hashmap " + hm.keySet());
        // UTF-8 and the fallback
        System.out.println(b.getString("utf"));
        ResourceBundle latin = ResourceBundle.getBundle("app.latin", Locale.ROOT);
        System.out.println(latin.getString("l") + " " + (int) latin.getString("l").charAt(0));
        PropertyResourceBundle p = new PropertyResourceBundle(new ByteArrayInputStream("k=é\nj=\\u00e9x".getBytes(StandardCharsets.UTF_8)));
        System.out.println(p.getString("k") + " " + p.getString("j") + " " + p.getLocale() + "|" + p.getBaseBundleName() + "|" + Collections.list(p.getKeys()));
        PropertyResourceBundle q = new PropertyResourceBundle(new StringReader("k=v"));
        System.out.println(q.getString("k") + " " + q.keySet());
        // list bundle by class
        ResourceBundle c = ResourceBundle.getBundle("app.Texts", Locale.UK);
        System.out.println(c.getClass().getName() + " " + c.getLocale() + " " + c.getString("a") + " " + c.getString("b") + " " + c.getString("c") + " " + Collections.list(c.getKeys()));
        System.out.println(c.getObject("n") + " " + Arrays.toString(c.getStringArray("arr")));
        ListResourceBundle direct = new Texts_en();
        System.out.println(direct.getLocale() + "|" + direct.getBaseBundleName() + "|" + direct.getString("b") + "|" + Collections.list(direct.getKeys()));
        try { direct.getString("a"); } catch (MissingResourceException ex) { System.out.println(ex.getMessage() + " | " + ex.getClassName() + " | " + ex.getKey()); }
    }
}
