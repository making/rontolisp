package app;

import java.util.*;

public class Main {
    static String show(ResourceBundle b) {
        if (b == null) return "null";
        List<String> keys = new ArrayList<>();
        for (Enumeration<String> e = b.getKeys(); e.hasMoreElements();) keys.add(e.nextElement());
        return b.getClass().getName() + " locale=[" + b.getLocale() + "] base=" + b.getBaseBundleName() + " keys=" + keys + " keySet=" + b.keySet();
    }
    static void get(String base, Locale l) {
        try {
            ResourceBundle b = l == null ? ResourceBundle.getBundle(base) : ResourceBundle.getBundle(base, l);
            System.out.println(base + " " + l + " -> " + show(b));
            for (String k : List.of("a", "b", "c", "only", "missing")) {
                try {
                    System.out.println("  " + k + "=" + b.getString(k) + " contains=" + b.containsKey(k));
                } catch (MissingResourceException ex) {
                    System.out.println("  " + k + " " + ex.getClass().getName() + ": " + ex.getMessage() + " | " + ex.getClassName() + " | " + ex.getKey());
                }
            }
        } catch (MissingResourceException ex) {
            System.out.println(base + " " + l + " " + ex.getClass().getName() + ": " + ex.getMessage() + " | " + ex.getClassName() + " | " + ex.getKey());
        }
    }
    public static void main(String[] args) throws Exception {
        get("app.msgs", Locale.UK);
        get("app.msgs", Locale.US);
        get("app.msgs", Locale.ROOT);
        get("app.msgs", Locale.ENGLISH);
        get("app.msgs", Locale.CANADA);
        get("app.msgs", null);
        get("app/msgs", Locale.UK);
        get("lib.texts", Locale.UK);
        get("nothing", Locale.UK);
        get("top", Locale.UK);
        get("app.only_base", Locale.UK);
        System.out.println(ResourceBundle.getBundle("app.msgs", Locale.UK) == ResourceBundle.getBundle("app.msgs", Locale.UK));
        System.out.println(ResourceBundle.getBundle("app.msgs", Locale.UK).getObject("a"));
        try {
            ResourceBundle.getBundle("app.msgs", Locale.UK).getStringArray("a");
        } catch (Exception ex) { System.out.println(ex.getClass().getName() + ": " + ex.getMessage()); }
        try {
            ResourceBundle.getBundle(null, Locale.UK);
        } catch (Exception ex) { System.out.println(ex.getClass().getName() + ": " + ex.getMessage()); }
        try {
            ResourceBundle.getBundle("app.msgs", (Locale) null);
        } catch (Exception ex) { System.out.println(ex.getClass().getName() + ": " + ex.getMessage()); }
        try {
            ResourceBundle.getBundle("app.msgs", Locale.UK).getString(null);
        } catch (Exception ex) { System.out.println(ex.getClass().getName() + ": " + ex.getMessage()); }
        System.out.println(lib.Lib.bundle());
    }
}
