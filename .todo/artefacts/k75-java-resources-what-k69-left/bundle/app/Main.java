package app;

import java.util.*;

public class Main {
    public static void main(String[] args) {
        String base = "app." + (args.length > 0 ? args[0] : "Texts");
        ResourceBundle b = ResourceBundle.getBundle(base, Locale.UK);
        System.out.println(b.getString("hello") + " " + b.getClass().getName());
        ResourceBundle r = ResourceBundle.getBundle(base, Locale.ROOT);
        System.out.println(r.getString("hello") + " " + r.getClass().getName());
    }
}
