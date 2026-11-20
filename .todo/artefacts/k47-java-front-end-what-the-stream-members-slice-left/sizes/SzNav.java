import java.util.*;

public class SzNav {
    public static void main(String[] args) {
        TreeMap<Integer, String> m = new TreeMap<>();
        for (int i = 0; i < 20; i++) {
            m.put(i * 7 % 23, "v" + i);
        }
        System.out.println(m.floorEntry(10) + " " + m.ceilingKey(11) + " " + m.headMap(5) + " " + m.descendingMap().firstKey() + " " + m.subMap(3, 9).size());
    }
}
