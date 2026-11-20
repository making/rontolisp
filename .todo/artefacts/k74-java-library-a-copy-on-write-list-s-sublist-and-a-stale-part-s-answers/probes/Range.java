import java.util.*;
import java.util.function.*;

public class Range {
    static void t(String n, Supplier<Object> s) {
        try { System.out.println(n + " = " + s.get()); }
        catch (Throwable e) { System.out.println(n + " ! " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    static final StringBuilder log = new StringBuilder();
    static class Exposed<E> extends ArrayList<E> {
        Exposed(Collection<E> c) { super(c); }
        void rr(int a, int b) { removeRange(a, b); }
        int mc() { return modCount; }
    }
    static class Watched<E> extends ArrayList<E> {
        Watched(Collection<E> c) { super(c); }
        protected void removeRange(int a, int b) { log.append("rr(" + a + "," + b + ") "); super.removeRange(a, b); }
    }
    static class Mine extends AbstractList<Integer> {
        final ArrayList<Integer> in = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        public Integer get(int i) { return in.get(i); }
        public int size() { return in.size(); }
        public void add(int i, Integer e) { in.add(i, e); modCount++; }
        public Integer remove(int i) { log.append("remove(" + i + ") "); modCount++; return in.remove(i); }
        int mc() { return modCount; }
        void rr(int a, int b) { removeRange(a, b); }
    }
    static class MineRR extends Mine {
        protected void removeRange(int a, int b) { log.append("rr(" + a + "," + b + ") "); super.removeRange(a, b); }
    }
    static class Quiet extends AbstractList<Integer> {
        final ArrayList<Integer> in = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        public Integer get(int i) { return in.get(i); }
        public int size() { return in.size(); }
        public Integer remove(int i) { log.append("remove(" + i + ") "); return in.remove(i); }
        void rr(int a, int b) { removeRange(a, b); }
    }
    static String run(Runnable r) { log.setLength(0); try { r.run(); } catch (Throwable e) { log.append("! " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); } return log.toString(); }
    public static void main(String[] a) {
        t("ArrayList rr(1,3)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(1, 3); return l + " " + l.mc(); });
        t("ArrayList rr(2,2)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(2, 2); return l + " " + l.mc(); });
        t("ArrayList rr(0,6)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(0, 6); return l + " " + l.mc(); });
        t("ArrayList rr(3,1)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(3, 1); return l + " " + l.mc(); });
        t("ArrayList rr(-1,1)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(-1, 1); return l + " " + l.mc(); });
        t("ArrayList rr(1,9)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(1, 9); return l + " " + l.mc(); });
        t("ArrayList rr(9,10)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(9, 10); return l + " " + l.mc(); });
        t("ArrayList rr(6,6)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(6, 6); return l + " " + l.mc(); });
        t("ArrayList rr(7,7)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(7, 7); return l + " " + l.mc(); });
        t("ArrayList rr(-2,-1)", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(-2, -1); return l + " " + l.mc(); });
        t("ArrayList rr(0,0) mc", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.rr(0, 0); return l.mc(); });
        t("ArrayList sub clear calls rr", () -> { var l = new Watched<>(List.of(0, 1, 2, 3, 4, 5)); log.setLength(0); l.subList(1, 3).clear(); return log + " " + l; });
        t("ArrayList clear calls rr", () -> { var l = new Watched<>(List.of(0, 1, 2, 3, 4, 5)); log.setLength(0); l.clear(); return log + " " + l; });
        t("ArrayList sub of sub clear calls rr", () -> { var l = new Watched<>(List.of(0, 1, 2, 3, 4, 5)); log.setLength(0); l.subList(1, 5).subList(1, 3).clear(); return log + " " + l; });
        t("ArrayList sub clear mc", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.subList(1, 3).clear(); return l.mc(); });
        t("ArrayList sub empty clear mc", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); l.subList(1, 1).clear(); return l.mc(); });
        t("ArrayList sub clear then use", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); var s = l.subList(1, 4); s.clear(); s.add(7); return l + " " + s; });
        t("ArrayList sub clear then parent use", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); var s = l.subList(1, 5); var u = s.subList(1, 3); u.clear(); return s + " " + u + " " + l; });
        t("ArrayList sub clear stale", () -> { var l = new Exposed<>(List.of(0, 1, 2, 3, 4, 5)); var s = l.subList(1, 4); l.add(1); s.clear(); return l; });
        t("Mine clear", () -> run(() -> { var m = new Mine(); m.clear(); log.append(m); }));
        t("Mine clear mc", () -> { var m = new Mine(); m.clear(); return m.mc(); });
        t("Mine sub clear", () -> run(() -> { var m = new Mine(); m.subList(1, 4).clear(); log.append(m); }));
        t("Mine sub of sub clear", () -> run(() -> { var m = new Mine(); m.subList(1, 5).subList(1, 3).clear(); log.append(m); }));
        t("Mine rr(1,3)", () -> run(() -> { var m = new Mine(); m.rr(1, 3); log.append(m); }));
        t("Mine rr(3,1)", () -> run(() -> { var m = new Mine(); m.rr(3, 1); log.append(m); }));
        t("Mine rr(-1,1)", () -> run(() -> { var m = new Mine(); m.rr(-1, 1); log.append(m); }));
        t("Mine rr(1,9)", () -> run(() -> { var m = new Mine(); m.rr(1, 9); log.append(m); }));
        t("Mine rr(7,9)", () -> run(() -> { var m = new Mine(); m.rr(7, 9); log.append(m); }));
        t("Mine rr(2,2)", () -> run(() -> { var m = new Mine(); m.rr(2, 2); log.append(m + " " + m.mc()); }));
        t("Mine rr(7,7)", () -> run(() -> { var m = new Mine(); m.rr(7, 7); log.append(m + " " + m.mc()); }));
        t("Mine rr(1,3) mc", () -> { var m = new Mine(); m.rr(1, 3); return m.mc(); });
        t("MineRR clear", () -> run(() -> { var m = new MineRR(); m.clear(); log.append(m); }));
        t("MineRR sub clear", () -> run(() -> { var m = new MineRR(); m.subList(1, 4).clear(); log.append(m); }));
        t("MineRR sub of sub clear", () -> run(() -> { var m = new MineRR(); m.subList(1, 5).subList(1, 3).clear(); log.append(m); }));
        t("MineRR sub empty clear", () -> run(() -> { var m = new MineRR(); m.subList(2, 2).clear(); log.append(m); }));
        t("MineRR sub clear stale", () -> run(() -> { var m = new MineRR(); var s = m.subList(1, 4); m.add(0, 9); s.clear(); log.append(m); }));
        t("Quiet clear", () -> run(() -> { var m = new Quiet(); m.clear(); log.append(m); }));
        t("Quiet rr(1,3)", () -> run(() -> { var m = new Quiet(); m.rr(1, 3); log.append(m); }));
        t("Quiet rr(2,2)", () -> run(() -> { var m = new Quiet(); m.rr(2, 2); log.append(m); }));
        t("Quiet sub clear", () -> run(() -> { var m = new Quiet(); m.subList(1, 4).clear(); log.append(m); }));
        t("LinkedList sub clear", () -> { var l = new LinkedList<>(List.of(0, 1, 2, 3, 4, 5)); var s = l.subList(1, 4); s.clear(); return l + " " + s; });
        t("LinkedList sub clear then add", () -> { var l = new LinkedList<>(List.of(0, 1, 2, 3, 4, 5)); var s = l.subList(1, 4); s.clear(); s.add(7); return l + " " + s; });
        t("Vector sub clear", () -> { var l = new Vector<>(List.of(0, 1, 2, 3, 4, 5)); var s = l.subList(1, 4); s.clear(); return l + " " + s; });
        t("Vector clear", () -> { var l = new Vector<>(List.of(0, 1, 2, 3, 4, 5)); l.clear(); return l; });
    }
}
