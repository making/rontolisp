import java.io.*;
import java.util.*;
import java.util.function.*;

public class Utils {
    interface Op { Object run() throws Exception; }

    static void t(String name, Op op) {
        try {
            System.out.println(name + " = " + op.run());
        } catch (Exception e) {
            System.out.println(name + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    record Point(int x, double w) { }

    public static void main(String[] args) {
        t("requireNonNullElseGet", () -> Objects.requireNonNullElseGet(null, () -> "made") + " " + Objects.requireNonNullElseGet("there", () -> "made"));
        t("requireNonNullElseGet null", () -> Objects.requireNonNullElseGet(null, () -> null));
        t("requireNonNullElseGet no supplier", () -> Objects.requireNonNullElseGet(null, null));
        t("requireNonNull supplier", () -> Objects.requireNonNull("x", () -> "message"));
        t("requireNonNull supplier null", () -> Objects.requireNonNull(null, () -> "the message"));
        t("requireNonNull supplier none", () -> Objects.requireNonNull(null, (Supplier<String>) null));
        t("toIdentityString", () -> Objects.checkIndex(1, 2) + " " + Objects.hash(1, "a", null) + " " + Objects.requireNonNullElse(null, 5));
        for (int d : new int[] {0, 1, 2, -1, 5, 7, -8, 100}) {
            List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4, 5));
            Collections.rotate(list, d);
            System.out.println("rotate " + d + " " + list);
        }
        List<Integer> none = new ArrayList<>();
        Collections.rotate(none, 3);
        System.out.println(none);
        List<Integer> big = new ArrayList<>(List.of(1, 2, 3, 1, 2, 3, 4, 1, 2));
        System.out.println(Collections.indexOfSubList(big, List.of(1, 2)) + " " + Collections.lastIndexOfSubList(big, List.of(1, 2)) + " " + Collections.indexOfSubList(big, List.of(3, 4)) + " " + Collections.indexOfSubList(big, List.of(9)) + " " + Collections.indexOfSubList(big, List.of()) + " " + Collections.lastIndexOfSubList(big, List.of()) + " " + Collections.indexOfSubList(big, big) + " " + Collections.indexOfSubList(List.of(1), big) + " " + Collections.lastIndexOfSubList(big, List.of(2, 3, 4)));
        System.out.println(Collections.replaceAll(big, 1, 9) + " " + big + " " + Collections.replaceAll(big, 7, 9) + " " + big);
        List<String> withNull = new ArrayList<>(Arrays.asList("a", null, "b", null));
        System.out.println(Collections.replaceAll(withNull, null, "z") + " " + withNull + " " + Collections.replaceAll(withNull, "z", null) + " " + withNull);
        List<String> names = new ArrayList<>(List.of("x", "yy", "zzz"));
        String[] array = names.toArray(String[]::new);
        System.out.println(array.length + " " + array[2] + " " + Arrays.toString(names.toArray(n -> new String[n])) + " " + Arrays.toString(new HashSet<>(names).toArray(String[]::new)) + " " + Arrays.toString(new ArrayDeque<>(names).toArray(String[]::new)));
        int[] ints = new int[5];
        Arrays.setAll(ints, i -> i * i);
        long[] longs = new long[4];
        Arrays.setAll(longs, i -> 1L << (i * 20));
        double[] doubles = new double[3];
        Arrays.setAll(doubles, i -> i / 2.0);
        String[] strings = new String[3];
        Arrays.setAll(strings, i -> "s" + i);
        System.out.println(Arrays.toString(ints) + " " + Arrays.toString(longs) + " " + Arrays.toString(doubles) + " " + Arrays.toString(strings));
        System.out.println(Arrays.hashCode(ints) + " " + Arrays.hashCode(longs) + " " + Arrays.hashCode(doubles) + " " + Arrays.hashCode(strings) + " " + Arrays.hashCode(new long[0]) + " " + Arrays.hashCode((long[]) null) + " " + Arrays.hashCode(new double[] {0.0, -0.0, Double.NaN, 1e300}) + " " + Arrays.hashCode(new char[] {'a', 'b'}) + " " + Arrays.hashCode(new boolean[] {true, false}) + " " + Arrays.hashCode(new byte[] {1, -2}) + " " + Arrays.hashCode(new short[] {1, -2}) + " " + Arrays.hashCode(new float[] {1.5f, -0.0f}) + " " + Arrays.hashCode(new long[] {-1L, Long.MIN_VALUE, 0x123456789L}));
        Object[] a = {1, "two", new int[] {3, 4}, new Object[] {"five", new long[] {6L}}, null, new String[] {"s"}, new double[] {1.5}};
        Object[] b = {1, "two", new int[] {3, 4}, new Object[] {"five", new long[] {6L}}, null, new String[] {"s"}, new double[] {1.5}};
        Object[] c = {1, "two", new int[] {3, 5}, new Object[] {"five", new long[] {6L}}, null, new String[] {"s"}, new double[] {1.5}};
        System.out.println(Arrays.deepEquals(a, b) + " " + Arrays.deepEquals(a, c) + " " + Arrays.equals(a, b) + " " + Arrays.deepEquals(null, null) + " " + Arrays.deepEquals(a, null) + " " + Arrays.deepEquals(new Object[] {new int[] {1}}, new Object[] {new long[] {1}}) + " " + Arrays.deepEquals(new Object[0], new Object[0]) + " " + Arrays.deepHashCode(a) + " " + Arrays.deepHashCode(c) + " " + Arrays.deepHashCode(null) + " " + Arrays.deepEquals(new Object[] {new char[] {'a'}, new boolean[] {true}, new byte[] {1}, new short[] {2}, new float[] {1f}}, new Object[] {new char[] {'a'}, new boolean[] {true}, new byte[] {1}, new short[] {2}, new float[] {1f}}));
        Map.Entry<String, Integer> entry = new AbstractMap.SimpleEntry<>("k", 1);
        Map.Entry<String, Integer> fixed = new AbstractMap.SimpleImmutableEntry<>("k", 1);
        System.out.println(entry + " " + entry.getKey() + " " + entry.getValue() + " " + entry.setValue(5) + " " + entry + " " + entry.hashCode() + " " + entry.equals(fixed) + " " + fixed.equals(Map.entry("k", 1)) + " " + fixed + " " + fixed.hashCode() + " " + new AbstractMap.SimpleEntry<>(fixed) + " " + new AbstractMap.SimpleEntry<String, String>(null, null) + " " + new AbstractMap.SimpleEntry<String, String>(null, null).hashCode() + " " + entry.equals("k=5"));
        t("immutable setValue", () -> fixed.setValue(2));
        t("Map.entry setValue", () -> Map.entry("a", 1).setValue(2));
        List<Point> points = new ArrayList<>(List.of(new Point(2, 0.5), new Point(1, 2.5), new Point(1, -1.0), new Point(2, 0.25), new Point(1, Double.NaN)));
        points.sort(Comparator.comparingInt(Point::x).thenComparingDouble(Point::w));
        System.out.println(points);
        points.sort(Comparator.comparingDouble(Point::w).thenComparingInt(Point::x).thenComparingLong(p -> p.x()));
        System.out.println(points);
        System.out.println(System.getProperty("line.separator").length() + " " + System.getProperty("no.such.property") + " " + System.getProperty("no.such.property", "fallback") + " " + System.getProperty("file.separator") + " " + System.getProperty("path.separator") + " " + System.getProperty("file.encoding") + " " + System.getProperty("stdout.encoding") + " " + System.getProperty("native.encoding"));
        t("getProperty null", () -> System.getProperty(null));
        t("getProperty empty", () -> System.getProperty(""));
        Runtime runtime = Runtime.getRuntime();
        System.out.println((runtime.availableProcessors() > 0) + " " + (runtime == Runtime.getRuntime()));
        runtime.gc();
        System.gc();
        StackTraceElement frame = new StackTraceElement("a.B", "run", "B.java", 12);
        System.out.println(frame + " " + frame.getClassName() + " " + frame.getMethodName() + " " + frame.getFileName() + " " + frame.getLineNumber() + " " + new StackTraceElement("a.B", "run", null, -1) + " " + new StackTraceElement("a.B", "run", "B.java", -1) + " " + new StackTraceElement("a.B", "run", "B.java", -2) + " " + frame.equals(new StackTraceElement("a.B", "run", "B.java", 12)) + " " + (frame.hashCode() == new StackTraceElement("a.B", "run", "B.java", 12).hashCode()));
        Throwable thrown = new IllegalStateException("s");
        thrown.setStackTrace(new StackTraceElement[] {frame});
        System.out.println(thrown.getMessage() + " " + (thrown.fillInStackTrace() == thrown));
        t("setStackTrace null", () -> { thrown.setStackTrace(new StackTraceElement[] {frame, null}); return "set"; });
        Throwable[] all = {new AssertionError(), new AssertionError("detail"), new AssertionError(5), new AssertionError((Object) null), new AssertionError('c'), new AssertionError(1.5), new AssertionError(true), new AssertionError(7L), new AssertionError("with cause", new RuntimeException("why")), new AssertionError(new RuntimeException("as detail")), new ArrayStoreException(), new ArrayStoreException("java.lang.Integer"), new StackOverflowError(), new StackOverflowError("deep"), new OutOfMemoryError(), new OutOfMemoryError("Java heap space"), new SecurityException(), new SecurityException("denied"), new SecurityException("denied", new RuntimeException("c")), new SecurityException(new RuntimeException("c")), new FileNotFoundException(), new FileNotFoundException("a.txt (No such file or directory)"), new InputMismatchException(), new InputMismatchException("For input string: \"x\""), new NegativeArraySizeException("-1"), new NumberFormatException(), new ArithmeticException(), new java.util.NoSuchElementException("ns"), new java.util.EmptyStackException()};
        for (Throwable each : all) {
            System.out.println(each + " | " + each.getMessage() + " | " + each.getCause() + " | " + (each instanceof RuntimeException) + " " + (each instanceof Error) + " " + (each instanceof IOException) + " " + (each instanceof VirtualMachineError) + " " + (each instanceof LinkageError) + " " + (each instanceof IllegalArgumentException) + " " + (each instanceof IndexOutOfBoundsException) + " " + (each instanceof IllegalStateException) + " " + (each instanceof NoSuchElementException));
        }
        try {
            throw new AssertionError("caught");
        } catch (Error e) {
            System.out.println("an Error: " + e);
        }
        IntToDoubleFunction half = i -> i / 2.0;
        IntToLongFunction wide = i -> (long) i << 33;
        LongToIntFunction narrow = l -> (int) (l >> 33);
        LongToDoubleFunction ld = l -> l * 0.5;
        DoubleToIntFunction di = d -> (int) d;
        DoubleToLongFunction dl = d -> (long) d;
        ObjIntConsumer<String> oi = (text, i) -> System.out.print(text + i + " ");
        ObjLongConsumer<String> ol = (text, l) -> System.out.print(text + l + " ");
        ObjDoubleConsumer<String> od = (text, d) -> System.out.print(text + d + " ");
        IntBinaryOperator ib = (x, y) -> x * y;
        BooleanSupplier bs = () -> true;
        ToIntBiFunction<String, String> tib = (x, y) -> x.length() + y.length();
        DoubleBinaryOperator db = (x, y) -> x + y;
        LongUnaryOperator lu = LongUnaryOperator.identity();
        IntUnaryOperator iu = IntUnaryOperator.identity().andThen(x -> x + 1).compose(x -> x * 2);
        IntPredicate ip = ((IntPredicate) x -> x > 2).negate().or(x -> x == 10).and(x -> x != 0);
        DoubleUnaryOperator du = DoubleUnaryOperator.identity().andThen(x -> x * 3);
        BiConsumer<String, Integer> bc = (x, y) -> System.out.print(x + y + " ");
        oi.accept("oi", 1);
        ol.accept("ol", 2L);
        od.accept("od", 3.5);
        bc.andThen((x, y) -> System.out.print(y + x + " ")).accept("bc", 4);
        System.out.println();
        System.out.println(half.applyAsDouble(3) + " " + wide.applyAsLong(1) + " " + narrow.applyAsInt(1L << 34) + " " + ld.applyAsDouble(3L) + " " + di.applyAsInt(3.9) + " " + dl.applyAsLong(-3.9) + " " + ib.applyAsInt(6, 7) + " " + bs.getAsBoolean() + " " + tib.applyAsInt("ab", "c") + " " + db.applyAsDouble(1, 2) + " " + lu.applyAsLong(5L) + " " + iu.applyAsInt(5) + " " + ip.test(1) + ip.test(10) + ip.test(0) + ip.test(5) + " " + du.applyAsDouble(2));
        System.out.println(String.format("%h %h %h %H %h %h %10h|%-10h|%h %h %h", "hello", 255, null, "hello", 3.5, 'c', "x", "y", true, 100000L, new int[0].length));
        t("%h precision", () -> String.format("%.2h|%.20h", 123456789, 1));
    }
}
