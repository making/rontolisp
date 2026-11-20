package lib;

import java.util.*;

public class Lib {
    public static String bundle() {
        ResourceBundle b = ResourceBundle.getBundle("lib.texts", Locale.UK);
        ResourceBundle t = ResourceBundle.getBundle("lib.texts", Locale.UK, Thread.currentThread().getContextClassLoader());
        return b.getString("a") + " / " + b.getLocale() + " / " + t.getString("a") + " / " + t.getLocale();
    }
}
