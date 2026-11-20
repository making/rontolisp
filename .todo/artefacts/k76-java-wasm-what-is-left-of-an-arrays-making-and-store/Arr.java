public class Arr {
    static long[] mk(int n) { return new long[n + 1]; }
    static int[] mki(int n) { return new int[n]; }
    static Object[] mko(int n) { return new Object[n]; }
    public static void main(String[] a) {
        System.out.println(mk(3).length + mki(4).length + mko(2).length);
    }
}
