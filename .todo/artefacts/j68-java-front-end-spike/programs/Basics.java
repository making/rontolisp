public class Basics {
    static int fib(int n) {
        if (n < 2) return n;
        return fib(n - 1) + fib(n - 2);
    }

    static int hash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    static double average(int[] xs) {
        double sum = 0;
        for (int x : xs) sum += x;
        return sum / xs.length;
    }

    static int add(int a, int b) { return a + b; }
    static double add(double a, double b) { return a + b; }
    static String add(String a, String b) { return a + b; }

    public static void main(String[] args) {
        System.out.println("fib(20) = " + fib(20));
        System.out.println(hash("The quick brown fox jumps over the lazy dog"));
        int big = Integer.MAX_VALUE;
        big++;
        System.out.println(big);
        long l = 1L << 40;
        System.out.println(l * 3 + " " + (int) l + " " + (7 / 2) + " " + (-7 / 2) + " " + (-7 % 3) + " " + 7.0 / 2);
        System.out.println((1 << 31) + " " + (-8 >> 1) + " " + (-8 >>> 28) + " " + (5 & 3) + " " + (5 ^ 3));
        int[] xs = {3, 1, 4, 1, 5, 9, 2, 6};
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j + 1 < xs.length - i; j++) {
                if (xs[j] > xs[j + 1]) {
                    int t = xs[j];
                    xs[j] = xs[j + 1];
                    xs[j + 1] = t;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int x : xs) {
            if (x == 4) continue;
            if (x == 9) break;
            sb.append(x).append(',');
        }
        System.out.println(sb.toString() + " avg=" + average(xs));
        System.out.println(add(1, 2) + " " + add(1.5, 2) + " " + add("a", "b"));
        char c = 'a';
        c += 2;
        System.out.println(c + " " + (c + 1) + " " + (char) (c + 1) + " " + (c > 'b' ? "yes" : "no"));
        int n = 0, i = 0;
        while (true) {
            i++;
            if (i % 2 == 0) continue;
            if (i > 10) break;
            n += i;
        }
        System.out.println(n + " " + Math.sqrt(2) + " " + Math.max(3, 7L) + " " + 1e10 + " " + 100.0 + " " + 0.001);
        boolean flag = n > 3 && !(i < 2);
        System.out.println(flag);
    }
}
