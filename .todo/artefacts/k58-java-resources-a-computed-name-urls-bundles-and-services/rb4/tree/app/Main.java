package app;

import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        ResourceBundle us = ResourceBundle.getBundle("app.msgs", Locale.US);
        ResourceBundle ca = ResourceBundle.getBundle("app.msgs", Locale.CANADA);
        ResourceBundle en = ResourceBundle.getBundle("app.msgs", Locale.ENGLISH);
        ResourceBundle root = ResourceBundle.getBundle("app.msgs", Locale.ROOT);
        System.out.println((us == ca) + " " + (us == en) + " " + (root == us) + " " + us.getLocale());
        ResourceBundle uk = ResourceBundle.getBundle("app.msgs", Locale.UK);
        System.out.println(uk.getLocale() + " " + (uk == us));
        // the chain's missing key
        ResourceBundle t = ResourceBundle.getBundle("app.Texts", Locale.UK);
        try { t.getString("missing"); } catch (MissingResourceException ex) { System.out.println(ex.getMessage() + " | " + ex.getClassName() + " | " + ex.getKey()); }
        try { uk.getString("missing"); } catch (MissingResourceException ex) { System.out.println(ex.getMessage() + " | " + ex.getClassName() + " | " + ex.getKey()); }
        try { uk.getObject(null); } catch (Exception ex) { System.out.println(ex.getClass().getName()); }
        try { uk.containsKey(null); } catch (Exception ex) { System.out.println(ex.getClass().getName()); }
        try { uk.getStringArray("a"); } catch (ClassCastException ex) { System.out.println("cce"); }
        // keySet order and size growth
        ResourceBundle many = ResourceBundle.getBundle("app.many", Locale.UK);
        System.out.println(many.keySet());
        Set<String> inc = new HashSet<>();
        for (String k : Collections.list(many.getKeys())) inc.add(k);
        System.out.println(inc);
        System.out.println(new HashSet<>(Collections.list(many.getKeys())));
        System.out.println(Collections.list(many.getKeys()));
        ResourceBundle list = ResourceBundle.getBundle("app.Lists", Locale.ROOT);
        System.out.println(Collections.list(list.getKeys()));
        HashMap<String, Object> h = new HashMap<>(); for (String k : List.of("k0","k1","k2","k3","k4","k5","k6","k7","k8","k9","k10","k11","k12","Aa","BB")) h.put(k, k);
        System.out.println("default hm " + h.keySet());
        HashMap<String, Object> h15 = new HashMap<>(15); for (String k : List.of("k0","k1","k2","k3","k4","k5","k6","k7","k8","k9","k10","k11","k12","Aa","BB")) h15.put(k, k);
        System.out.println("hm(15)     " + h15.keySet());
        ResourceBundle.clearCache();
        System.out.println(ResourceBundle.getBundle("app.msgs", Locale.US) == us);
    }
}
