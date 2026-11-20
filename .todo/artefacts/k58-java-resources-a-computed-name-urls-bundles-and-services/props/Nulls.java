import java.io.*;
import java.util.*;

public class Nulls {
    interface R { void run() throws Exception; }
    static void t(String what, R r) { try { r.run(); System.out.println(what + " ok"); } catch (Exception ex) { System.out.println(what + " " + ex.getClass().getName() + ": " + ex.getMessage()); } }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        t("load stream", () -> p.load((InputStream) null));
        t("load reader", () -> p.load((Reader) null));
        t("store writer", () -> p.store((Writer) null, "x"));
        t("store stream", () -> p.store((OutputStream) null, "x"));
        t("cap -1", () -> new Properties(-1));
        t("list null", () -> p.list((PrintStream) null));
        t("put null", () -> p.put(null, "x"));
        t("put null value", () -> p.put("x", null));
        t("get null", () -> p.get(null));
        t("getProperty null", () -> p.getProperty(null));
        t("getProperty default null", () -> System.out.println(p.getProperty("none", null)));
        t("defaults null", () -> System.out.println(new Properties(null).getProperty("x")));
        p.setProperty("a", "1");
        t("store a stream", () -> { ByteArrayOutputStream o = new ByteArrayOutputStream(); p.store(o, null); System.out.println(o.size() > 0); });
        Properties q = new Properties();
        q.load(new StringReader("a=1\nb=2\n"));
        q.load(new StringReader("b=3\nc=4"));
        System.out.println(q);
        System.out.println(q.remove("a") + " " + q.putIfAbsent("b", "x") + " " + q.getOrDefault("z", "zz") + " " + q.merge("c", "5", (x, y) -> x + "+" + y) + " " + q);
    }
}
