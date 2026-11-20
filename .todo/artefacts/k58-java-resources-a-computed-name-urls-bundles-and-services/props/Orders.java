import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Orders {
    public static void main(String[] args) throws Exception {
        Properties defaults = new Properties();
        for (int i = 0; i < 12; i++) defaults.setProperty("d" + i * 7, "dv" + i);
        defaults.setProperty("k3", "shadowed");
        Properties p = new Properties(defaults);
        for (int i = 0; i < 15; i++) p.setProperty("k" + i, "v" + i);
        p.put("obj", 3);
        p.put(5, "five");
        System.out.println("own        " + p.keySet());
        System.out.println("names      " + p.stringPropertyNames());
        Set<String> all = new TreeSet<>(p.stringPropertyNames());
        System.out.println("hashmap    " + new HashMap<String,String>() {{ for (String k : all) put(k, k); }}.keySet());
        Hashtable<String,Object> ht = new Hashtable<>(); for (String k : all) ht.put(k, k);
        System.out.println("hashtable  " + ht.keySet());
        try { System.out.println("propnames " + Collections.list(p.propertyNames())); } catch (Exception ex) { System.out.println("propnames " + ex.getClass().getName()); }
        Properties q = new Properties(defaults);
        for (int i = 0; i < 15; i++) q.setProperty("k" + i, "v" + i);
        System.out.println("propnames " + Collections.list(q.propertyNames()));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        q.list(new PrintStream(o));
        System.out.println("list " + o.toString().replace("\n", " / "));
        StringWriter w = new StringWriter();
        q.list(new PrintWriter(w));
        System.out.println("listw " + w.toString().replace("\n", " / "));
        // getProperty of a non-String own value with a default
        Properties d2 = new Properties(); d2.setProperty("k", "default");
        Properties p2 = new Properties(d2); p2.put("k", 3);
        System.out.println("nonstring " + p2.getProperty("k") + " " + p2.getProperty("k", "fb") + " " + p2.stringPropertyNames());
        // a long key in list
        Properties p3 = new Properties();
        p3.setProperty("0123456789012345678901234567890123456789012345", "v");
        p3.list(System.out);
        // store with non-String
        try { p.store(new StringWriter(), null); } catch (Exception ex) { System.out.println("store " + ex.getClass().getName()); }
        // store own entries only, with defaults
        StringWriter s = new StringWriter();
        Properties p4 = new Properties(defaults); p4.setProperty("b", "2"); p4.setProperty("a", "1"); p4.setProperty("C", "3"); p4.setProperty("", "e");
        p4.store(s, null);
        System.out.println(s.toString().replaceAll("#[A-Z][a-z][a-z] .*\n", "#DATE\n").replace("\n", " / "));
        System.out.println(p4.size() + " " + p4.isEmpty() + " " + p4.containsKey("d0") + " " + p4.get("d0") + " " + p4.contains("1") + " " + p4.containsValue("1"));
        System.out.println(Collections.list(p4.keys()) + " " + Collections.list(p4.elements()) + " " + p4.values() + " " + p4.entrySet());
    }
}
