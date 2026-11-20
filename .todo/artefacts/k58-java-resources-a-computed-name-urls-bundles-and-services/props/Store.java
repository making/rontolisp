import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Store {
    static String show(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n') b.append("<n>\n"); else if (c < 0x20 || c > 0x7e) b.append(String.format("<%x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.setProperty("plain", "value");
        p.setProperty("with space", " lead and trail ");
        p.setProperty("eq=colon:hash#bang!", "eq=colon:hash#bang!");
        p.setProperty("#start", "!start");
        p.setProperty("ctl\t\n\r\f", "ctl\t\n\r\f\b");
        p.setProperty("unié∑", "unié∑😀");
        p.setProperty("back\\slash", "back\\slash");
        p.setProperty("", "");
        p.setProperty("tilde~del\u007f", "\u007f~");
        StringWriter w = new StringWriter();
        p.store(w, "comment line\nsecond\r\nthird\rfourth # é∑ ! x\n#already\n!bang");
        System.out.println("== store(Writer)");
        System.out.print(show(w.toString()));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        p.store(o, "cé∑");
        System.out.println("== store(OutputStream)");
        System.out.print(show(new String(o.toByteArray(), StandardCharsets.ISO_8859_1)));
        StringWriter n = new StringWriter();
        p.store(n, null);
        System.out.println("== store(null)");
        System.out.print(show(n.toString()));
        System.out.println("== list");
        Properties l = new Properties();
        l.setProperty("short", "v");
        l.setProperty("long", "0123456789012345678901234567890123456789");
        l.setProperty("long41", "01234567890123456789012345678901234567890");
        l.setProperty("k37", "0123456789012345678901234567890123456");
        l.list(System.out);
        l.put("obj", Integer.valueOf(3));
        l.put(Integer.valueOf(4), "int key");
        System.out.println("== toString " + l);
        System.out.println("names " + l.stringPropertyNames() + " " + l.getProperty("obj") + " " + l.get("obj"));
        Properties defaults = new Properties();
        defaults.setProperty("d1", "default one");
        defaults.setProperty("short", "shadowed");
        Properties withDefaults = new Properties(defaults);
        withDefaults.setProperty("short", "own");
        System.out.println(withDefaults.getProperty("d1") + " " + withDefaults.getProperty("short") + " " + withDefaults.get("d1") + " " + withDefaults.getProperty("none", "fallback") + " " + withDefaults.getProperty("d1", "fallback"));
        System.out.println(withDefaults.stringPropertyNames() + " " + Collections.list(withDefaults.propertyNames()) + " " + withDefaults + " " + withDefaults.size());
        withDefaults.list(System.out);
        System.out.println(withDefaults.setProperty("short", "again") + " " + withDefaults.setProperty("new", "n"));
        try { withDefaults.stringPropertyNames().add("x"); } catch (Exception ex) { System.out.println(ex.getClass().getName() + " " + ex.getMessage()); }
        try { p.setProperty(null, "x"); } catch (Exception ex) { System.out.println(ex.getClass().getName() + " " + ex.getMessage()); }
        try { p.setProperty("x", null); } catch (Exception ex) { System.out.println(ex.getClass().getName() + " " + ex.getMessage()); }
        try { p.getProperty(null); } catch (Exception ex) { System.out.println(ex.getClass().getName() + " " + ex.getMessage()); }
        try { p.load((Reader) null); } catch (Exception ex) { System.out.println(ex.getClass().getName() + " " + ex.getMessage()); }
        Properties eq1 = new Properties(); eq1.setProperty("a", "1");
        Properties eq2 = new Properties(defaults); eq2.setProperty("a", "1");
        Map<Object,Object> hm = new HashMap<>(); hm.put("a", "1");
        System.out.println(eq1.equals(eq2) + " " + eq1.equals(hm) + " " + hm.equals(eq1) + " " + eq1.hashCode() + " " + hm.hashCode());
        Properties big = new Properties();
        for (int i = 0; i < 40; i++) big.setProperty("key" + i, "v" + i);
        System.out.println(big.keySet());
        java.util.concurrent.ConcurrentHashMap<Object,Object> chm = new java.util.concurrent.ConcurrentHashMap<>();
        for (int i = 0; i < 40; i++) chm.put("key" + i, "v" + i);
        System.out.println(chm.keySet());
        java.util.concurrent.ConcurrentHashMap<Object,Object> chm8 = new java.util.concurrent.ConcurrentHashMap<>(8);
        for (int i = 0; i < 40; i++) chm8.put("key" + i, "v" + i);
        System.out.println(chm8.keySet());
        System.out.println(big.entrySet().iterator().next().getClass().getName() + " " + big.keySet().getClass().getName());
        Properties cap = new Properties(100);
        for (int i = 0; i < 40; i++) cap.setProperty("key" + i, "v" + i);
        System.out.println(cap.keySet());
    }
}
