import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Bins {
    static String keys(int... ks) {
        Map<Integer, Integer> c = new ConcurrentHashMap<>();
        for (int k : ks) c.put(k, k);
        return c.keySet().toString();
    }
    public static void main(String[] args) {
        // 11 keys: no growth yet. bin 0 holds 0,16,32,48 in which order?
        System.out.println(keys(0, 16, 32, 48, 1, 17, 2, 3, 4, 5, 6));
        // the 12th key grows 16 -> 32: bin 0 {0,32}, bin 16 {16,48}
        System.out.println(keys(0, 16, 32, 48, 1, 17, 2, 3, 4, 5, 6, 7));
        System.out.println(keys(48, 32, 16, 0, 1, 17, 2, 3, 4, 5, 6, 7));
        System.out.println(keys(0, 32, 64, 16, 48, 80, 2, 3, 4, 5, 6, 7));
        System.out.println(keys(16, 0, 48, 32, 80, 64, 2, 3, 4, 5, 6, 7));
        System.out.println(keys(0, 16, 32, 64, 48, 96, 80, 3, 4, 5, 6, 7));
        System.out.println(keys(0, 16, 48, 32, 64, 96, 80, 112, 4, 5, 6, 7));
        // 8 in one bin, then a 9th
        System.out.println(keys(0, 16, 32, 48, 64, 80, 96, 112));
        System.out.println(keys(0, 16, 32, 48, 64, 80, 96, 112, 128));
        System.out.println(keys(0, 16, 32, 48, 64, 80, 96, 112, 128, 144));
        Map<Integer, Integer> h = new HashMap<>();
        for (int k : new int[] {0, 16, 32, 48, 64, 80, 96, 112, 128}) h.put(k, k);
        System.out.println(h.keySet());
    }
}
