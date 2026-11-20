import java.util.*;

public class Wide {
    public static void main(String[] args) {
        double[][] ranges = { { -Double.MAX_VALUE, Double.MAX_VALUE }, { -1e308, 1e308 }, { -Double.MAX_VALUE, 0 }, { -1, Double.MAX_VALUE }, { -1e308, 9e307 } , {-3, 5}, {0, 1e-300}};
        int[] ok = new int[5];
        int total = 0;
        for (int seed = 0; seed < 2000; seed++) {
            for (double[] g : ranges) {
                double o = g[0], b = g[1];
                double r = new Random(seed).nextDouble();
                double x = new Random(seed).nextDouble(o, b);
                total++;
                double[] cand = { b - o < Double.POSITIVE_INFINITY ? r * (b - o) + o : (r * (b / 2 - o / 2) + o / 2) * 2, r * b + (1 - r) * o, (r * (b / 2 - o / 2)) * 2 + o, o + r * (b * 0.5 - o * 0.5) * 2, r * (b - o) + o };
                for (int c = 0; c < cand.length; c++) {
                    double v = cand[c];
                    if (v >= b) v = Math.nextDown(b);
                    if (Double.doubleToLongBits(v) == Double.doubleToLongBits(x)) ok[c]++;
                }
            }
        }
        System.out.println(total + " " + Arrays.toString(ok));
        float[][] franges = { { -Float.MAX_VALUE, Float.MAX_VALUE }, { -3e38f, 3e38f }, { -Float.MAX_VALUE, 0 }, {-3, 5}, {0, 1e-30f} };
        int[] fok = new int[5];
        int ftotal = 0;
        for (int seed = 0; seed < 2000; seed++) {
            for (float[] g : franges) {
                float o = g[0], b = g[1];
                float r = new Random(seed).nextFloat();
                float x = new Random(seed).nextFloat(o, b);
                ftotal++;
                float[] cand = { b - o < Float.POSITIVE_INFINITY ? r * (b - o) + o : (r * (b / 2 - o / 2) + o / 2) * 2, r * b + (1 - r) * o, (r * (b / 2 - o / 2)) * 2 + o, o + r * (b * 0.5f - o * 0.5f) * 2, r * (b - o) + o };
                for (int c = 0; c < cand.length; c++) {
                    float v = cand[c];
                    if (v >= b) v = Math.nextDown(b);
                    if (Float.floatToIntBits(v) == Float.floatToIntBits(x)) fok[c]++;
                }
            }
        }
        System.out.println(ftotal + " " + Arrays.toString(fok));
        int bok = 0;
        for (int seed = 0; seed < 2000; seed++) {
            for (float b : new float[] { 1f, 10f, 0.5f, 3e38f, 1e-30f }) {
                float r = new Random(seed).nextFloat();
                float x = new Random(seed).nextFloat(b);
                float v = r * b;
                if (v >= b) v = Math.nextDown(b);
                if (Float.floatToIntBits(v) == Float.floatToIntBits(x)) bok++;
            }
        }
        System.out.println("float bound " + bok + "/10000");
    }
}
