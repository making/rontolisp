import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class Copy2 {
    static String lengths(Map<Integer, Integer> map) {
        List<Integer> walk = new ArrayList<>(map.keySet());
        StringBuilder out = new StringBuilder();
        for (int len = 16; len <= 4096; len <<= 1) {
            boolean ascending = true;
            for (int i = 1; i < walk.size() && ascending; i++) ascending = (walk.get(i - 1) & (len - 1)) <= (walk.get(i) & (len - 1));
            if (ascending) out.append(len).append('/');
        }
        return out.toString();
    }
    public static void main(String[] args) {
        int[] keys = {1024, 1600, 3712, 1152, 2944, 4112, 528, 1616, 4944, 4048, 288, 6240, 3680, 992, 4720, 368, 3504, 16, 32, 48, 64, 80, 96, 112, 128, 144};
        for (int n = 7; n <= 26; n++) {
            Map<Integer, Integer> more = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) more.put(keys[i], 0);
            ConcurrentHashMap<Integer, Integer> a = new ConcurrentHashMap<>(more);
            ConcurrentHashMap<Integer, Integer> b = new ConcurrentHashMap<>();
            for (int k : more.keySet()) b.put(k, 0);
            ConcurrentHashMap<Integer, Integer> c = new ConcurrentHashMap<>();
            c.putAll(more);
            System.out.println(n + " copy " + lengths(a) + " puts " + lengths(b) + " putAll " + lengths(c) + (a.keySet().toString().equals(b.keySet().toString()) ? " same" : " differ"));
        }
    }
}
