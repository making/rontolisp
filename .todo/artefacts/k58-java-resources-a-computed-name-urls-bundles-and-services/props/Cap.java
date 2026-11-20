import java.util.*;
import java.util.concurrent.*;

public class Cap {
    public static void main(String[] args) {
        for (int cap : new int[] { 0, 1, 5, 12, 13, 100 }) {
            Properties p = new Properties(cap);
            ConcurrentHashMap<Object, Object> c = new ConcurrentHashMap<>(cap);
            for (int i = 0; i < 40; i++) { p.setProperty("key" + i, "v"); c.put("key" + i, "v"); }
            System.out.println(cap + " " + p.keySet().toString().equals(c.keySet().toString()));
        }
        Properties d = new Properties();
        ConcurrentHashMap<Object, Object> c = new ConcurrentHashMap<>();
        for (int i = 0; i < 300; i++) { d.setProperty("k" + i * 31, "v"); c.put("k" + i * 31, "v"); }
        System.out.println("default " + d.keySet().toString().equals(c.keySet().toString()));
    }
}
