import java.io.*;
import java.util.*;

public class Orders2 {
    public static void main(String[] args) throws Exception {
        Properties deeper = new Properties();
        for (int i = 0; i < 9; i++) deeper.setProperty("x" + i * 3, "xv" + i);
        deeper.setProperty("Aa", "deep Aa");
        Properties defaults = new Properties(deeper);
        for (int i = 0; i < 12; i++) defaults.setProperty("d" + i * 7, "dv" + i);
        defaults.setProperty("BB", "def BB");
        Properties q = new Properties(defaults);
        for (int i = 0; i < 15; i++) q.setProperty("k" + i, "v" + i);
        q.setProperty("C#", "own");
        System.out.println("propnames " + Collections.list(q.propertyNames()));
        Hashtable<Object,Object> sim = new Hashtable<>();
        for (Object k : deeper.keySet()) sim.put(k, 1);
        for (Object k : defaults.keySet()) sim.put(k, 1);
        for (Object k : q.keySet()) sim.put(k, 1);
        System.out.println("simulated " + sim.keySet());
        System.out.println("names     " + q.stringPropertyNames());
        HashMap<Object,Object> hm = new HashMap<>();
        for (Object k : deeper.keySet()) hm.put(k, 1);
        for (Object k : defaults.keySet()) hm.put(k, 1);
        for (Object k : q.keySet()) hm.put(k, 1);
        System.out.println("simulated " + hm.keySet());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        q.list(new PrintStream(o));
        System.out.println("list " + o.toString().replace("\n", " / "));
        Properties nv = new Properties();
        nv.setProperty("a", "1");
        nv.put("n", 42);
        nv.put("arr", new int[0].getClass());
        try { nv.list(System.out); } catch (Exception ex) { System.out.println("list " + ex); }
        Properties nk = new Properties();
        nk.put(7, "seven");
        try { nk.list(System.out); } catch (Exception ex) { System.out.println("list key " + ex.getClass().getName()); }
        try { System.out.println(Collections.list(nv.propertyNames())); } catch (Exception ex) { System.out.println("propnames " + ex.getClass().getName()); }
        System.out.println(nv.stringPropertyNames());
    }
}
