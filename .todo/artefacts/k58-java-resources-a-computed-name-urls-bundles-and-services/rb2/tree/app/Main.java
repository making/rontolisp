package app;

import java.util.*;

public class Main {
    static void get(String base, Locale l) {
        try {
            ResourceBundle b = l == null ? ResourceBundle.getBundle(base) : ResourceBundle.getBundle(base, l);
            List<String> chain = new ArrayList<>();
            System.out.println(base + " [" + l + "] -> [" + b.getLocale() + "] a=" + b.getString("a") + " keys=" + Collections.list(b.getKeys()));
        } catch (MissingResourceException ex) {
            System.out.println(base + " [" + l + "] " + ex.getMessage() + " | " + ex.getClassName());
        }
    }
    public static void main(String[] args) throws Exception {
        System.out.println("default " + Locale.getDefault());
        get("app.gb", Locale.US);
        get("app.gb", Locale.ROOT);
        get("app.gb", Locale.ENGLISH);
        get("app.gb", null);
        get("app.us", Locale.UK);
        get("app.us", Locale.ROOT);
        get("app.us", null);
        get("app.nobase", Locale.UK);
        get("app.nobase", Locale.US);
        get("app.nobase", Locale.ROOT);
        get("app.nobase", Locale.ENGLISH);
        get("app.enonly", Locale.ROOT);
        get("app.enonly", Locale.UK);
    }
}
