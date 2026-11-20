import java.util.*;
import java.util.function.*;

public class Enums {
    enum Day { MON, TUE, WED, THU, FRI, SAT, SUN }

    enum Empty { }

    enum Big { B0, B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13, B14, B15, B16, B17, B18, B19, B20, B21, B22, B23, B24, B25, B26, B27, B28, B29, B30, B31, B32, B33, B34, B35, B36, B37, B38, B39, B40, B41, B42, B43, B44, B45, B46, B47, B48, B49, B50, B51, B52, B53, B54, B55, B56, B57, B58, B59, B60, B61, B62, B63, B64, B65, B66, B67, B68, B69 }

    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + (e instanceof NullPointerException ? "" : " " + e.getMessage()));
        }
    }

    static String marks(Spliterator<?> s) {
        int c = s.characteristics();
        StringBuilder b = new StringBuilder();
        String[] names = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] bits = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        for (int i = 0; i < bits.length; i++) {
            if ((c & bits[i]) != 0) {
                b.append(b.length() == 0 ? "" : "|").append(names[i]);
            }
        }
        return b + " " + s.estimateSize();
    }

    public static void main(String[] args) {
        EnumSet<Day> none = EnumSet.noneOf(Day.class);
        EnumSet<Day> all = EnumSet.allOf(Day.class);
        EnumSet<Day> some = EnumSet.of(Day.FRI, Day.MON, Day.WED);
        EnumSet<Day> range = EnumSet.range(Day.TUE, Day.FRI);
        EnumSet<Day> comp = EnumSet.complementOf(some);
        EnumSet<Day> copy = EnumSet.copyOf(List.of(Day.SUN, Day.MON));
        EnumSet<Day> copy2 = EnumSet.copyOf(some);
        System.out.println(none + " " + all + " " + some + " " + range + " " + comp + " " + copy + " " + copy2 + " " + EnumSet.of(Day.SAT));
        System.out.println(some.contains(Day.WED) + " " + some.contains(Day.TUE) + " " + some.contains(null) + " " + some.contains("WED") + " " + some.add(Day.TUE) + " " + some.add(Day.TUE) + " " + some.remove(Day.MON) + " " + some.remove(Day.MON) + " " + some.remove(null) + " " + some + " " + some.size());
        System.out.println(some.equals(EnumSet.of(Day.TUE, Day.WED, Day.FRI)) + " " + some.equals(new HashSet<>(List.of(Day.TUE, Day.WED, Day.FRI))) + " " + some.hashCode() + " " + (some.hashCode() == new HashSet<>(some).hashCode()) + " " + some.containsAll(range) + " " + range.containsAll(some));
        EnumSet<Day> mixed = EnumSet.copyOf(all);
        System.out.println(mixed.removeAll(some) + " " + mixed + " " + mixed.retainAll(EnumSet.of(Day.SUN, Day.MON)) + " " + mixed + " " + mixed.addAll(range) + " " + mixed + " " + mixed.removeIf(d -> d.ordinal() % 2 == 0) + " " + mixed);
        attempt("add null", () -> some.add(null));
        attempt("of null", () -> EnumSet.of(Day.MON, (Day) null));
        attempt("range backwards", () -> EnumSet.range(Day.FRI, Day.TUE));
        attempt("copyOf empty collection", () -> EnumSet.copyOf(new ArrayList<Day>()));
        attempt("copyOf empty enum set", () -> EnumSet.copyOf(EnumSet.noneOf(Day.class)));
        attempt("noneOf empty enum", () -> EnumSet.allOf(Empty.class));
        Iterator<Day> it = all.iterator();
        StringBuilder seen = new StringBuilder();
        while (it.hasNext()) {
            Day d = it.next();
            seen.append(d).append(' ');
            if (d == Day.TUE) {
                all.remove(Day.WED);
                all.remove(Day.MON);
                it.remove();
            }
        }
        System.out.println(seen + "" + all);
        attempt("remove twice", () -> {
            Iterator<Day> i = all.iterator();
            i.next();
            i.remove();
            i.remove();
            return null;
        });
        System.out.println(marks(all.spliterator()) + " | " + marks(EnumSet.noneOf(Day.class).spliterator()) + " " + all.getClass().getName() + " " + EnumSet.allOf(Big.class).getClass().getName() + " " + (all instanceof AbstractSet));
        EnumSet<Big> big = EnumSet.range(Big.B3, Big.B66);
        big.removeAll(EnumSet.range(Big.B10, Big.B60));
        System.out.println(big + " " + big.size() + " " + EnumSet.complementOf(big).size() + " " + big.contains(Big.B65) + " " + EnumSet.allOf(Big.class).size());
        // EnumMap
        EnumMap<Day, Integer> m = new EnumMap<>(Day.class);
        m.put(Day.SUN, 7);
        m.put(Day.MON, 1);
        m.put(Day.WED, 3);
        m.merge(Day.MON, 10, Integer::sum);
        System.out.println(m + " " + m.size() + " " + m.get(Day.WED) + " " + m.get(Day.TUE) + " " + m.get("WED") + " " + m.get(null) + " " + m.containsKey(Day.SUN) + " " + m.containsKey(null) + " " + m.containsValue(3) + " " + m.keySet() + " " + m.values() + " " + m.entrySet());
        System.out.println(m.remove(Day.WED) + " " + m.remove(Day.WED) + " " + m.remove(null) + " " + m.remove("x") + " " + m.put(Day.SUN, 70) + " " + m + " " + m.equals(Map.of(Day.MON, 11, Day.SUN, 70)) + " " + Map.of(Day.MON, 11, Day.SUN, 70).equals(m) + " " + m.hashCode());
        m.put(Day.TUE, null);
        System.out.println(m + " " + m.containsKey(Day.TUE) + " " + m.containsValue(null) + " " + m.get(Day.TUE) + " " + m.size());
        attempt("put null key", () -> m.put(null, 1));
        EnumMap<Day, Integer> m2 = new EnumMap<>(m);
        EnumMap<Day, Integer> m3 = new EnumMap<>(Map.of(Day.SAT, 6, Day.THU, 4));
        System.out.println(m2 + " " + m3 + " " + m2.equals(m));
        attempt("copy of empty map", () -> new EnumMap<Day, Integer>(new HashMap<Day, Integer>()));
        attempt("copy of empty enum map", () -> new EnumMap<Day, Integer>(new EnumMap<Day, Integer>(Day.class)));
        Iterator<Map.Entry<Day, Integer>> entries = m.entrySet().iterator();
        Map.Entry<Day, Integer> first = entries.next();
        first.setValue(100);
        entries.next();
        entries.remove();
        System.out.println(first + " " + m);
        StringBuilder walked = new StringBuilder();
        for (Day d : m.keySet()) {
            walked.append(d).append(' ');
            if (d == Day.MON) {
                m.put(Day.FRI, 5);
                m.remove(Day.SUN);
            }
        }
        System.out.println(walked + "" + m);
        System.out.println(marks(m.keySet().spliterator()) + " | " + marks(m.values().spliterator()) + " | " + marks(m.entrySet().spliterator()) + " " + m.getClass().getName());
        Map.Entry<Day, Integer> e = m.entrySet().iterator().next();
        m.remove(e.getKey());
        System.out.println(e + " " + m);
    }
}
