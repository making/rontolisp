package app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {
    static String err(Runnable r) {
        try { r.run(); return "ok"; } catch (RuntimeException e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
    }
    public static void main(String[] args) throws Exception {
        ResourceBundle.Control c = ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_DEFAULT);
        System.out.println(c.getFormats("x") + " " + ResourceBundle.Control.FORMAT_CLASS + " " + ResourceBundle.Control.FORMAT_PROPERTIES + " " + ResourceBundle.Control.TTL_DONT_CACHE + " " + ResourceBundle.Control.TTL_NO_EXPIRATION_CONTROL);
        System.out.println(c.getCandidateLocales("x", Locale.UK) + " " + c.getCandidateLocales("x", Locale.ROOT) + " " + c.getCandidateLocales("x", Locale.ENGLISH));
        System.out.println(c.getFallbackLocale("x", Locale.UK) + " " + c.getFallbackLocale("x", Locale.US) + " " + ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES).getFallbackLocale("x", Locale.UK));
        System.out.println(c.toBundleName("a.b", Locale.UK) + " " + c.toBundleName("a.b", Locale.ROOT) + " " + c.toResourceName("a.b_en", "properties") + " " + c.toResourceName("a/b", "x.y") + " " + c.getTimeToLive("x", Locale.UK));
        System.out.println(err(() -> ResourceBundle.Control.getControl(List.of("x"))) + " | " + err(() -> ResourceBundle.Control.getControl(null)) + " | " + err(() -> ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT).getFormats(null)) + " | " + err(() -> c.getCandidateLocales(null, Locale.UK)) + " | " + err(() -> c.getCandidateLocales("x", null)) + " | " + err(() -> c.toBundleName(null, Locale.UK)) + " | " + err(() -> c.toResourceName(null, "p")) + " | " + err(() -> ResourceBundle.Control.getControl(new ArrayList<>(List.of("java.class", "java.properties")))));
        System.out.println((ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_DEFAULT) == c) + " " + c.getClass().getName() + " " + ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT).getClass().getName() + " " + ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_PROPERTIES).getFormats("x"));
        ResourceBundle b = ResourceBundle.getBundle("app.m", Locale.UK, c);
        System.out.println(b.getLocale() + " " + b.getString("a") + " " + b.getString("b"));
        b = ResourceBundle.getBundle("app.m", Locale.US, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
        System.out.println(b.getLocale() + " " + b.getString("a"));
        ResourceBundle.Control utf = new ResourceBundle.Control() {
            public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload) throws IOException, IllegalAccessException, InstantiationException {
                System.out.println("newBundle " + baseName + " [" + locale + "] " + format + " " + reload);
                if (!format.equals("java.properties")) return null;
                InputStream in = loader.getResourceAsStream(toResourceName(toBundleName(baseName, locale), "properties"));
                if (in == null) return null;
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) { return new PropertyResourceBundle(r); }
            }
            public List<Locale> getCandidateLocales(String baseName, Locale locale) {
                List<Locale> l = super.getCandidateLocales(baseName, locale);
                System.out.println("candidates " + baseName + " [" + locale + "] " + l);
                return l;
            }
            public Locale getFallbackLocale(String baseName, Locale locale) {
                Locale f = super.getFallbackLocale(baseName, locale);
                System.out.println("fallback " + baseName + " [" + locale + "] " + f);
                return f;
            }
            public List<String> getFormats(String baseName) {
                System.out.println("formats " + baseName);
                return super.getFormats(baseName);
            }
            public long getTimeToLive(String baseName, Locale locale) {
                System.out.println("ttl " + baseName + " [" + locale + "]");
                return TTL_DONT_CACHE;
            }
        };
        b = ResourceBundle.getBundle("app.u", Locale.UK, utf);
        System.out.println(b.getLocale() + " " + b.getString("a") + " " + b.getClass().getName() + " " + b.getBaseBundleName());
        b = ResourceBundle.getBundle("app.u", Locale.UK, utf);
        System.out.println(b.getLocale() + " " + b.getString("a"));
        try { ResourceBundle.getBundle("app.none", Locale.UK, utf); } catch (MissingResourceException e) { System.out.println(e.getMessage() + " | " + e.getClassName() + " | " + e.getKey()); }
        System.out.println(err(() -> ResourceBundle.getBundle("app.m", Locale.UK, (ResourceBundle.Control) null)) + " | " + err(() -> ResourceBundle.getBundle("app.m", (ResourceBundle.Control) null)));
        b = ResourceBundle.getBundle("app.m", c);
        System.out.println(b.getLocale() + " " + b.getString("a"));
        b = ResourceBundle.getBundle("app.m", Locale.UK, Main.class.getClassLoader(), c);
        System.out.println(b.getLocale() + " " + b.getString("a"));
        ResourceBundle d = c.newBundle("app.m", Locale.UK, "java.properties", Main.class.getClassLoader(), false);
        System.out.println(d.getClass().getName() + " " + d.getLocale() + " " + d.getString("a") + " " + d.getBaseBundleName() + " " + c.newBundle("app.m", Locale.UK, "java.class", Main.class.getClassLoader(), false) + " " + err(() -> { try { c.newBundle("app.m", Locale.UK, "x", Main.class.getClassLoader(), false); } catch (Exception e) { throw new RuntimeException(e.getClass().getSimpleName() + " " + e.getMessage()); } }));
        System.out.println(Main.class.getModule().isNamed() + " " + Main.class.getModule().getName() + " " + (Main.class.getModule().getClassLoader() == Main.class.getClassLoader()) + " " + String.class.getModule() + " " + Main.class.getModule().toString().startsWith("unnamed module @"));
        b = ResourceBundle.getBundle("app.m", Main.class.getModule());
        System.out.println(b.getLocale() + " " + b.getString("a"));
        b = ResourceBundle.getBundle("app.m", Locale.UK, Main.class.getModule());
        System.out.println(b.getLocale() + " " + b.getString("a"));
        System.out.println(err(() -> ResourceBundle.getBundle("app.m", String.class.getModule())));
        System.out.println(err(() -> ResourceBundle.getBundle("app.m", Locale.UK, (Module) null)));
    }
}
