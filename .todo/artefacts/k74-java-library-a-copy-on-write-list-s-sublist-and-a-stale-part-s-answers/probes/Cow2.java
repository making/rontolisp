import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Cow2 {
    static void t(String n, Supplier<Object> s) {
        try { System.out.println(n + " = " + s.get()); }
        catch (Throwable e) { System.out.println(n + " ! " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static CopyOnWriteArrayList<String> fresh() { return new CopyOnWriteArrayList<>(List.of("a", "b", "c", "d", "e", "f")); }
    public static void main(String[] a) {
        t("get(9) stale", () -> { var l = fresh(); var s = l.subList(1, 3); l.add("x"); return s.get(9); });
        t("get(0) stale", () -> { var l = fresh(); var s = l.subList(1, 3); l.add("x"); return s.get(0); });
        t("set(9) stale", () -> { var l = fresh(); var s = l.subList(1, 3); l.add("x"); return s.set(9, "q"); });
        t("add(9) stale", () -> { var l = fresh(); var s = l.subList(1, 3); l.add("x"); s.add(9, "q"); return 1; });
        t("remove(9) stale", () -> { var l = fresh(); var s = l.subList(1, 3); l.add("x"); return s.remove(9); });
        t("subList(0,9) stale", () -> { var l = fresh(); var s = l.subList(1, 3); l.add("x"); return s.subList(0, 9); });
        t("set equal distinct", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); l.set(1, new String("b")); return u.get(0); });
        t("set same ref", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); l.set(1, "b"); return u.get(0); });
        t("part set same ref", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.set(0, "b"); return u.get(0); });
        t("part set diff", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.set(0, "z"); return s.get(0) + u.get(0); });
        t("list set null", () -> { var l = fresh(); var u = l.subList(1, 3); l.set(1, null); return u.get(0); });
        t("list null set null", () -> { var l = new CopyOnWriteArrayList<String>(); l.add(null); var u = l.subList(0, 1); l.set(0, null); return u.get(0); });
        t("part clear then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.clear(); return u.get(0); });
        t("part clear empty then", () -> { var l = fresh(); var s = l.subList(1, 1); var u = l.subList(1, 3); s.clear(); return u.get(0); });
        t("list clear empty then", () -> { var l = new CopyOnWriteArrayList<String>(); var u = l.subList(0, 0); l.clear(); return u.size(); });
        t("list clear nonempty then", () -> { var l = fresh(); var u = l.subList(0, 0); l.clear(); return u.size(); });
        t("part removeIf none then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.removeIf(x -> false); return u.get(0); });
        t("part removeIf some then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.removeIf(x -> x.equals("b")); return u.get(0); });
        t("part removeIf outside range", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); l.removeIf(x -> x.equals("f")); return u.get(0); });
        t("part removeAll none then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.removeAll(List.of("q")); return u.get(0); });
        t("part retainAll all then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.retainAll(List.of("b", "c")); return u.get(0); });
        t("part replaceAll then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.replaceAll(x -> x); return u.get(0); });
        t("part sort then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.sort(null); return u.get(0); });
        t("part addAll empty then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.addAll(List.of()); return u.get(0); });
        t("part addAll(i) empty then", () -> { var l = fresh(); var s = l.subList(1, 3); var u = l.subList(1, 3); s.addAll(1, List.of()); return u.get(0); });
        t("list sort desc then part", () -> { var l = fresh(); var s = l.subList(1, 3); l.sort(Comparator.reverseOrder()); return s.get(0); });
        t("removeAll null", () -> fresh().subList(1, 3).removeAll(null));
        t("retainAll null", () -> fresh().subList(1, 3).retainAll(null));
        t("removeIf null", () -> fresh().subList(1, 3).removeIf(null));
        t("replaceAll null", () -> { fresh().subList(1, 3).replaceAll(null); return 1; });
        t("addAll null", () -> fresh().subList(1, 3).addAll(null));
        t("addAll(i) null", () -> fresh().subList(1, 3).addAll(1, null));
        t("addAll(i) null empty-range", () -> fresh().subList(1, 3).addAll(9, null));
        t("forEach null", () -> { fresh().subList(1, 3).forEach(null); return 1; });
        t("sort bad comparator then part", () -> { var l = new CopyOnWriteArrayList<Object>(List.of("a", 1)); var s = l.subList(0, 2); try { s.sort(null); } catch (Exception e) { return e.getClass().getSimpleName() + " " + s.get(0); } return 1; });
        t("replaceAll throwing then part", () -> { var l = fresh(); var s = l.subList(1, 3); try { s.replaceAll(x -> { throw new IllegalStateException(); }); } catch (Exception e) { return e.getClass().getSimpleName() + " " + s.get(0) + l; } return 1; });
        t("removeIf throwing", () -> { var l = fresh(); var s = l.subList(1, 3); try { s.removeIf(x -> { throw new IllegalStateException(); }); } catch (Exception e) { return e.getClass().getSimpleName() + " " + s.get(0) + l; } return 1; });
        t("list.removeIf throwing then part", () -> { var l = fresh(); var s = l.subList(1, 3); try { l.removeIf(x -> { throw new IllegalStateException(); }); } catch (Exception e) { return e.getClass().getSimpleName() + " " + s.get(0) + l; } return 1; });
        t("list.sort(null) on 1 then part", () -> { var l = new CopyOnWriteArrayList<String>(List.of("a")); var s = l.subList(0, 1); l.sort(null); return s.get(0); });
        t("list.sort(null) on empty then part", () -> { var l = new CopyOnWriteArrayList<String>(); var s = l.subList(0, 0); l.sort(null); return s.size(); });
        t("list.replaceAll on empty then part", () -> { var l = new CopyOnWriteArrayList<String>(); var s = l.subList(0, 0); l.replaceAll(x -> x); return s.size(); });
        t("list.removeIf on empty then part", () -> { var l = new CopyOnWriteArrayList<String>(); var s = l.subList(0, 0); l.removeIf(x -> true); return s.size(); });
        t("list.addAll(i, empty) then part", () -> { var l = fresh(); var s = l.subList(1, 3); l.addAll(1, List.of()); return s.size(); });
        t("list.addAllAbsent present then part", () -> { var l = fresh(); var s = l.subList(1, 3); l.addAllAbsent(List.of("a", "b")); return s.size(); });
        t("list.addAllAbsent new then part", () -> { var l = fresh(); var s = l.subList(1, 3); l.addAllAbsent(List.of("q", "q")); return s.size(); });
        t("list.addAllAbsent new twice", () -> { var l = fresh(); l.addAllAbsent(List.of("q", "q")); return l; });
        t("list.retainAll empty", () -> { var l = fresh(); var s = l.subList(1, 3); l.retainAll(List.of()); return s.size(); });
        t("list.add(i) bad", () -> { fresh().add(9, "q"); return 1; });
        t("list.remove(9)", () -> fresh().remove(9));
        t("list.set(9)", () -> fresh().set(9, "q"));
        t("list.get(9)", () -> fresh().get(9));
        t("list.listIterator(9)", () -> fresh().listIterator(9));
        t("list.addAll(9, ..)", () -> fresh().addAll(9, List.of("q")));
        t("list.addAll(i, ..)", () -> { var l = fresh(); var r = l.addAll(2, List.of("q", "r")); return r + " " + l; });
        t("list.addAll(9, empty)", () -> fresh().addAll(9, List.of()));
        t("list.addAll(2, empty)", () -> fresh().addAll(2, List.of()));
        t("list.removeRange via sublist clear then part of list", () -> { var l = fresh(); var s = l.subList(0, 6); s.clear(); return l + " " + s.size(); });
    }
}
