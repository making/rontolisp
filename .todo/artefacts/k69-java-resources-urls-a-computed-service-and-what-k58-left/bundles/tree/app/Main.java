package app;
import java.util.*;
public class Main {
    static String show(String base, Locale l) {
        try { ResourceBundle b = ResourceBundle.getBundle(base, l); return base + " " + l + ": " + b.getClass().getName() + " [" + b.getLocale() + "] " + b.getString("a"); }
        catch (Exception e) { return base + " " + l + ": " + e; }
    }
    public static void main(String[] args) throws Exception {
        for (Locale l : new Locale[] { Locale.UK, Locale.ENGLISH, Locale.FRANCE, Locale.GERMANY, Locale.ROOT })
            System.out.println(show("app.Outer$Texts", l));
        System.out.println(show("app.Outer.Texts", Locale.UK));
        System.out.println(show("app.Outer$In$Deep", Locale.UK));
        System.out.println(show("app.Outer.In.Deep", Locale.UK));
        System.out.println(show("app.Outer$Dot", Locale.UK));
        System.out.println(show("app.Outer.Dot", Locale.UK));
        String base = args.length > 5 ? "x" : "app.Outer$";
        System.out.println(show(base + "Texts", Locale.UK));
        Class<?> c = args.length > 5 ? String.class : Runnable.class;
        System.out.println(ServiceLoader.load(c).stream().map(p -> p.type().getName()).toList());
        Class<? extends Comparable> k = args.length > 5 ? Integer.class : String.class;
        System.out.println(ServiceLoader.load(k).stream().map(p -> p.type().getName()).toList());
    }
    public static class Run implements Runnable { public void run() {} }
}
