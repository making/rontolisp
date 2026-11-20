import java.util.*;
import java.util.function.*;

public class Enums2 {
    enum Day { MON, TUE, WED, THU, FRI, SAT, SUN }

    enum Big { B0, B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13, B14, B15, B16, B17, B18, B19, B20, B21, B22, B23, B24, B25, B26, B27, B28, B29, B30, B31, B32, B33, B34, B35, B36, B37, B38, B39, B40, B41, B42, B43, B44, B45, B46, B47, B48, B49, B50, B51, B52, B53, B54, B55, B56, B57, B58, B59, B60, B61, B62, B63, B64, B65, B66, B67, B68, B69 }

    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + (e instanceof NullPointerException ? "" : " " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        EnumMap<Day, String> m = new EnumMap<>(Day.class);
        m.put(Day.TUE, "t");
        m.put(Day.THU, "h");
        m.put(Day.SAT, "s");
        Iterator<Map.Entry<Day, String>> it = m.entrySet().iterator();
        Map.Entry<Day, String> a = it.next();
        it.remove();
        attempt("entry after remove", () -> a.toString());
        attempt("getKey after remove", () -> a.getKey());
        attempt("getValue after remove", () -> a.getValue());
        attempt("setValue after remove", () -> a.setValue("x"));
        attempt("hashCode after remove", () -> a.hashCode());
        attempt("equals after remove", () -> a.equals(Map.entry(Day.TUE, "t")));
        Map.Entry<Day, String> b = it.next();
        m.remove(Day.THU);
        attempt("setValue after map remove", () -> b.setValue("x"));
        System.out.println(m + " " + b + " " + b.equals(Map.entry(Day.THU, "x")) + " " + m.containsKey(Day.THU));
        attempt("remove after remove", () -> {
            it.remove();
            it.remove();
            return m;
        });
        attempt("next past end", () -> {
            Iterator<Day> k = m.keySet().iterator();
            k.next();
            k.next();
            return null;
        });
        m.clear();
        m.put(Day.MON, "m");
        Map.Entry<Day, String> c = m.entrySet().iterator().next();
        System.out.println(c + " " + c.equals(new AbstractMap.SimpleEntry<>(Day.MON, "m")) + " " + c.hashCode() + " " + (c.hashCode() == (Day.MON.hashCode() ^ "m".hashCode())));
        m.keySet().remove(Day.MON);
        m.put(Day.FRI, "f");
        m.values().remove("f");
        System.out.println(m + " " + m.keySet().contains(Day.FRI) + " " + m.values().contains("f") + " " + m.entrySet().contains(Map.entry(Day.FRI, "f")));
        m.put(Day.WED, "w");
        m.put(Day.SUN, null);
        System.out.println(m.entrySet().contains(new AbstractMap.SimpleEntry<>(Day.SUN, null)) + " " + m.entrySet().remove(new AbstractMap.SimpleEntry<>(Day.SUN, null)) + " " + m + " " + m.entrySet().remove(Map.entry(Day.WED, "x")) + " " + m);
        // jumbo iteration under change
        EnumSet<Big> big = EnumSet.of(Big.B1, Big.B2, Big.B63, Big.B64, Big.B65, Big.B69);
        StringBuilder seen = new StringBuilder();
        for (Big x : big) {
            seen.append(x).append(' ');
            if (x == Big.B1) {
                big.remove(Big.B2);
                big.add(Big.B3);
                big.remove(Big.B65);
                big.add(Big.B66);
            }
        }
        System.out.println(seen + "" + big);
        EnumSet<Day> small = EnumSet.of(Day.MON, Day.TUE, Day.SAT);
        seen.setLength(0);
        for (Day d : small) {
            seen.append(d).append(' ');
            if (d == Day.MON) {
                small.add(Day.WED);
                small.remove(Day.SAT);
            }
        }
        System.out.println(seen + "" + small);
        Iterator<Big> bi = EnumSet.range(Big.B62, Big.B66).iterator();
        bi.next();
        bi.remove();
        attempt("jumbo remove twice", () -> {
            bi.remove();
            return null;
        });
        System.out.println(EnumSet.of(Day.MON, Day.TUE, Day.WED, Day.THU, Day.FRI, Day.SAT) + " " + EnumSet.of(Day.SUN, Day.MON, Day.TUE, Day.WED, Day.THU, Day.FRI, Day.SAT));
    }
}
