import java.util.function.Supplier;

public class Members {

    static void show(String what, Supplier<Object> made) {
        try {
            System.out.println(what + ": " + made.get());
        } catch (RuntimeException e) {
            // a NullPointerException of the run time has no message: its class is what is compared
            System.out.println(what + ": " + e.getClass().getName() + (e instanceof NullPointerException ? "" : ": " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        int[] ints = {0, 1, -1, 7, -7, 2, -2, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : ints) {
            for (int y : ints) {
                show("divideExact(" + x + "," + y + ")", () -> Math.divideExact(x, y));
                show("floorDivExact(" + x + "," + y + ")", () -> Math.floorDivExact(x, y));
                show("ceilDivExact(" + x + "," + y + ")", () -> Math.ceilDivExact(x, y));
            }
        }
        long[] longs = {0, 1, -1, 7, -7, 2, -2, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long x : longs) {
            for (long y : longs) {
                show("divideExact(" + x + "L," + y + "L)", () -> Math.divideExact(x, y));
                show("floorDivExact(" + x + "L," + y + "L)", () -> Math.floorDivExact(x, y));
                show("ceilDivExact(" + x + "L," + y + "L)", () -> Math.ceilDivExact(x, y));
            }
        }
        show("strict", () -> StrictMath.divideExact(7, 2) + " " + StrictMath.floorDivExact(-7L, 2L) + " " + StrictMath.ceilDivExact(7, 2));
        String s = "abcabc";
        int[] edges = {-1, 0, 1, 2, 3, 5, 6, 7};
        for (int a : edges) {
            for (int b : edges) {
                show("codePointCount(" + a + "," + b + ")", () -> s.codePointCount(a, b));
                show("offsetByCodePoints(" + a + "," + b + ")", () -> s.offsetByCodePoints(a, b - 3));
                show("indexOf(c," + a + "," + b + ")", () -> s.indexOf('c', a, b));
                show("indexOf(bc," + a + "," + b + ")", () -> s.indexOf("bc", a, b));
                show("indexOf(empty," + a + "," + b + ")", () -> s.indexOf("", a, b));
            }
        }
        show("indexOf(z)", () -> s.indexOf('z', 0, 6) + " " + s.indexOf("z", 0, 6) + " " + s.indexOf(0x1F600, 0, 6));
        show("indexOf(null)", () -> s.indexOf(null, 0, 6));
        char[] room = new char[4];
        for (int cp : new int[] {0x41, 0xFFFF, 0x10000, 0x1F600, 0x10FFFF, 0x110000, -1}) {
            for (int at : new int[] {-1, 0, 2, 3, 4}) {
                show("toChars(" + Integer.toHexString(cp) + "," + at + ")", () -> Character.toChars(cp, room, at) + " " + (int) room[Math.max(0, Math.min(at, 3))]);
            }
        }
        char[] pair = {'a', 0xD83D, 0xDE00, 0xDE00, 0xD83D};
        for (int at : new int[] {-1, 0, 1, 2, 3, 4, 5}) {
            show("codePointAt(" + at + ")", () -> Integer.toHexString(Character.codePointAt(pair, at)));
        }
        show("codePointAt(null)", () -> Character.codePointAt((char[]) null, 0));
        for (char c : new char[] {0, 1, 'a', 0x1234, 0xFF00, 0xFFFF}) {
            show("reverseBytes(" + (int) c + ")", () -> (int) Character.reverseBytes(c));
        }
        show("getenv", () -> System.getenv("RONTOLISP_NO_SUCH_VARIABLE") + " " + System.getenv("K05_PROBE"));
        java.util.OptionalLong some = java.util.OptionalLong.of(42);
        java.util.OptionalLong none = java.util.OptionalLong.empty();
        show("optional", () -> some + " " + none + " " + some.isPresent() + none.isPresent() + some.isEmpty() + none.isEmpty() + " " + some.getAsLong() + " " + some.orElse(7) + none.orElse(7)
                + " " + some.orElseGet(() -> 8L) + none.orElseGet(() -> 8L) + " " + some.orElseThrow() + " " + some.equals(java.util.OptionalLong.of(42)) + some.equals(none) + none.equals(java.util.OptionalLong.empty())
                + some.equals(42L) + " " + some.hashCode() + " " + none.hashCode() + " " + java.util.OptionalLong.of(Long.MIN_VALUE).hashCode());
        show("no value", () -> none.getAsLong());
        show("no value thrown", () -> none.orElseThrow());
        show("no value of its own", () -> none.orElseThrow(() -> new IllegalStateException("mine")));
        StringBuilder seen = new StringBuilder();
        some.ifPresent(v -> seen.append(v));
        none.ifPresent(v -> seen.append("never"));
        some.ifPresentOrElse(v -> seen.append('+').append(v), () -> seen.append("never"));
        none.ifPresentOrElse(v -> seen.append("never"), () -> seen.append("+none"));
        show("if present", () -> seen);
        java.net.http.HttpHeaders fields = java.net.http.HttpHeaders.of(java.util.Map.of("Content-Length", java.util.List.of("12", "x"), "X-Bad", java.util.List.of("1.5")), (name, value) -> true);
        show("as long", () -> fields.firstValueAsLong("content-length") + " " + fields.firstValueAsLong("absent"));
        show("no long", () -> fields.firstValueAsLong("x-bad"));
    }
}
