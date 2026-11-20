import java.util.*;

public class Enums3 {
    enum Day { MON, TUE, WED }
    enum Color { RED, GREEN }

    public static void main(String[] args) {
        EnumMap<Day, String> m = new EnumMap<>(Day.class);
        m.put(Day.MON, "m");
        m.put(Day.TUE, "t");
        Map.Entry<Day, String> e = m.entrySet().iterator().next();
        m.remove(Day.MON);
        System.out.println(m.size() + " " + e.setValue("again") + " " + m.size() + " " + m + " " + m.containsKey(Day.MON) + " " + m.entrySet().size() + " " + m.keySet() + " " + m.isEmpty());
        m.remove(Day.MON);
        System.out.println(m.size() + " " + m);
        m.remove(Day.TUE);
        System.out.println(m.size() + " " + m + " " + m.isEmpty());
        System.out.println(EnumSet.noneOf(Day.class).equals(EnumSet.noneOf(Color.class)) + " " + EnumSet.allOf(Day.class).equals(EnumSet.allOf(Color.class)) + " " + new EnumMap<Day, String>(Day.class).equals(new EnumMap<Color, String>(Color.class)) + " " + EnumSet.of(Day.MON).equals(Set.of(Day.MON)) + " " + Set.of(Day.MON).equals(EnumSet.of(Day.MON)));
        EnumMap<Day, String> n = new EnumMap<>(Day.class);
        n.put(Day.WED, "w");
        n.putAll(Map.of(Day.MON, "m"));
        n.putIfAbsent(Day.TUE, "t");
        n.computeIfAbsent(Day.TUE, d -> "x");
        n.compute(Day.WED, (d, v) -> null);
        System.out.println(n + " " + n.size() + " " + n.getOrDefault(Day.WED, "none"));
    }
}
