import java.util.*;
import java.util.function.*;

enum EnumOrderDay { MON, TUE, WED, THU, FRI, SAT, SUN }

enum EnumOrderBig { B0, B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13, B14, B15, B16, B17, B18, B19, B20, B21, B22, B23, B24, B25, B26, B27, B28, B29, B30, B31, B32, B33, B34, B35, B36, B37, B38, B39, B40, B41, B42, B43, B44, B45, B46, B47, B48, B49, B50, B51, B52, B53, B54, B55, B56, B57, B58, B59, B60, B61, B62, B63, B64, B65, B66, B67, B68, B69 }

enum EnumOrderShape {
    CIRCLE {
        int corners() {
            return 0;
        }
    },
    SQUARE {
        int corners() {
            return 4;
        }
    };

    abstract int corners();
}

class EnumOrder {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + (e instanceof NullPointerException ? "" : " " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        // a set of constants, in their order
        EnumSet<EnumOrderDay> some = EnumSet.of(EnumOrderDay.FRI, EnumOrderDay.MON, EnumOrderDay.WED);
        System.out.println(EnumSet.noneOf(EnumOrderDay.class) + " " + EnumSet.allOf(EnumOrderDay.class) + " " + some + " " + EnumSet.range(EnumOrderDay.TUE, EnumOrderDay.FRI) + " " + EnumSet.complementOf(some) + " " + EnumSet.copyOf(List.of(EnumOrderDay.SUN, EnumOrderDay.MON)) + " " + EnumSet.of(EnumOrderDay.SUN, EnumOrderDay.MON, EnumOrderDay.TUE, EnumOrderDay.WED, EnumOrderDay.THU, EnumOrderDay.FRI));
        System.out.println(some.contains(EnumOrderDay.WED) + " " + some.contains(null) + " " + some.contains("WED") + " " + some.add(EnumOrderDay.TUE) + " " + some.add(EnumOrderDay.TUE) + " " + some.remove(EnumOrderDay.MON) + " " + some.remove(null) + " " + some + " " + some.size() + " " + some.equals(new HashSet<>(some)) + " " + (some.hashCode() == new HashSet<>(some).hashCode()));
        EnumSet<EnumOrderDay> all = EnumSet.allOf(EnumOrderDay.class);
        System.out.println(all.removeAll(some) + " " + all + " " + all.retainAll(List.of(EnumOrderDay.SUN, EnumOrderDay.MON)) + " " + all + " " + all.removeIf(d -> d.ordinal() > 3) + " " + all + " " + EnumSet.allOf(EnumOrderShape.class) + " " + EnumSet.of(EnumOrderShape.SQUARE).contains(EnumOrderShape.SQUARE));
        attempt("add null", () -> some.add(null));
        attempt("range backwards", () -> EnumSet.range(EnumOrderDay.FRI, EnumOrderDay.TUE));
        attempt("copyOf nothing", () -> EnumSet.copyOf(new ArrayList<EnumOrderDay>()));
        attempt("copyOf an empty set", () -> EnumSet.copyOf(EnumSet.noneOf(EnumOrderDay.class)));
        // an iterator reads a word of the set when it comes to it
        EnumSet<EnumOrderDay> walked = EnumSet.allOf(EnumOrderDay.class);
        StringBuilder seen = new StringBuilder();
        for (Iterator<EnumOrderDay> it = walked.iterator(); it.hasNext(); ) {
            EnumOrderDay d = it.next();
            seen.append(d).append(' ');
            if (d == EnumOrderDay.TUE) {
                walked.remove(EnumOrderDay.WED);
                it.remove();
            }
        }
        EnumSet<EnumOrderBig> big = EnumSet.of(EnumOrderBig.B1, EnumOrderBig.B2, EnumOrderBig.B63, EnumOrderBig.B64, EnumOrderBig.B65, EnumOrderBig.B69);
        for (EnumOrderBig b : big) {
            seen.append(b).append(' ');
            if (b == EnumOrderBig.B1) {
                big.remove(EnumOrderBig.B2);
                big.remove(EnumOrderBig.B65);
                big.add(EnumOrderBig.B66);
            }
        }
        System.out.println(seen + "" + walked + " " + big + " " + EnumSet.complementOf(EnumSet.range(EnumOrderBig.B2, EnumOrderBig.B66)) + " " + EnumSet.allOf(EnumOrderBig.class).size());
        // a map from constants: a slot each
        EnumMap<EnumOrderDay, Integer> m = new EnumMap<>(EnumOrderDay.class);
        m.put(EnumOrderDay.SUN, 7);
        m.put(EnumOrderDay.MON, 1);
        m.put(EnumOrderDay.WED, 3);
        m.merge(EnumOrderDay.MON, 10, Integer::sum);
        System.out.println(m + " " + m.get(EnumOrderDay.WED) + " " + m.get("WED") + " " + m.get(null) + " " + m.containsKey(null) + " " + m.containsValue(3) + " " + m.keySet() + " " + m.values() + " " + m.remove(EnumOrderDay.WED) + " " + m.remove("x") + " " + m.equals(Map.of(EnumOrderDay.MON, 11, EnumOrderDay.SUN, 7)));
        m.put(EnumOrderDay.TUE, null);
        System.out.println(m + " " + m.containsKey(EnumOrderDay.TUE) + " " + m.size() + " " + new EnumMap<>(Map.of(EnumOrderDay.SAT, 6, EnumOrderDay.THU, 4)) + " " + new EnumMap<>(m).equals(m));
        attempt("put null", () -> m.put(null, 1));
        attempt("copy of an empty map", () -> new EnumMap<EnumOrderDay, Integer>(new HashMap<EnumOrderDay, Integer>()));
        attempt("copy of an empty enum map", () -> new EnumMap<EnumOrderDay, Integer>(new EnumMap<EnumOrderDay, Integer>(EnumOrderDay.class)));
        StringBuilder keys = new StringBuilder();
        for (EnumOrderDay d : m.keySet()) {
            keys.append(d).append(' ');
            if (d == EnumOrderDay.MON) {
                m.put(EnumOrderDay.FRI, 5);
                m.remove(EnumOrderDay.SUN);
            }
        }
        Iterator<Map.Entry<EnumOrderDay, Integer>> entries = m.entrySet().iterator();
        Map.Entry<EnumOrderDay, Integer> first = entries.next();
        first.setValue(100);
        entries.next();
        entries.remove();
        System.out.println(keys + "" + first + " " + m);
        attempt("an entry its iterator removed", () -> {
            Iterator<Map.Entry<EnumOrderDay, Integer>> again = m.entrySet().iterator();
            Map.Entry<EnumOrderDay, Integer> gone = again.next();
            again.remove();
            return gone.getValue();
        });
        Map.Entry<EnumOrderDay, Integer> held = m.entrySet().iterator().next();
        m.remove(held.getKey());
        System.out.println(held + " " + m + " " + m.size());
        held.setValue(1);
        System.out.println(m + " " + m.size() + " " + m.containsKey(held.getKey()));
    }
}
