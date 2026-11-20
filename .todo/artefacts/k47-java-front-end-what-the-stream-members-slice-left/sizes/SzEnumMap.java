import java.util.*;

public class SzEnumMap {
    enum Day { MON, TUE, WED, THU, FRI }

    public static void main(String[] args) {
        Map<Day, Integer> m = new EnumMap<>(Day.class);
        m.put(Day.WED, 3);
        m.merge(Day.MON, 1, Integer::sum);
        for (Map.Entry<Day, Integer> e : m.entrySet()) {
            System.out.println(e.getKey() + " " + e.getValue());
        }
    }
}
