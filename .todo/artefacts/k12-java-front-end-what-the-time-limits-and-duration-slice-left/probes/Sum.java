import java.util.*;
import java.util.stream.*;

public class Sum {
    static double simple(double[] a) {
        double s = 0;
        for (double x : a) s += x;
        return s;
    }

    // Kahan's compensated summation, as the algorithm is published
    static double kahan(double[] a) {
        double s = 0, c = 0;
        for (double x : a) {
            double y = x - c;
            double t = s + y;
            c = (t - s) - y;
            s = t;
        }
        return s;
    }

    // the same, the last compensation subtracted from the sum at the end
    static double kahanEnd(double[] a) {
        double s = 0, c = 0;
        for (double x : a) {
            double y = x - c;
            double t = s + y;
            c = (t - s) - y;
            s = t;
        }
        return s - c;
    }

    static double neumaier(double[] a) {
        double s = 0, c = 0;
        for (double x : a) {
            double t = s + x;
            if (Math.abs(s) >= Math.abs(x)) c += (s - t) + x;
            else c += (x - t) + s;
            s = t;
        }
        return s + c;
    }

    public static void main(String[] args) {
        Random r = new Random(7);
        int[] agree = new int[4];
        int total = 0;
        for (int round = 0; round < 20000; round++) {
            int n = 1 + r.nextInt(40);
            double[] a = new double[n];
            int kind = r.nextInt(4);
            for (int i = 0; i < n; i++) {
                a[i] = switch (kind) {
                    case 0 -> r.nextInt(100) / 10.0;
                    case 1 -> r.nextDouble() * Math.pow(10, r.nextInt(30) - 15) * (r.nextBoolean() ? 1 : -1);
                    case 2 -> r.nextGaussian();
                    default -> (r.nextInt(2000) - 1000) * 0.01;
                };
            }
            double jdk = DoubleStream.of(a).sum();
            total++;
            if (jdk == simple(a)) agree[0]++;
            if (jdk == kahan(a)) agree[1]++;
            if (jdk == kahanEnd(a)) agree[2]++;
            if (jdk == neumaier(a)) agree[3]++;
            double avg = DoubleStream.of(a).average().getAsDouble();
            if (avg != jdk / n) System.out.println("average differs from sum / n");
            double viaCollector = Arrays.stream(a).boxed().collect(Collectors.summingDouble(x -> x));
            if (viaCollector != jdk) System.out.println("summingDouble differs");
            double viaStats = DoubleStream.of(a).summaryStatistics().getSum();
            if (viaStats != jdk) System.out.println("statistics differ");
            double viaAvg = Arrays.stream(a).boxed().collect(Collectors.averagingDouble(x -> x));
            if (viaAvg != avg) System.out.println("averagingDouble differs");
        }
        System.out.println(total + " simple " + agree[0] + " kahan " + agree[1] + " kahanEnd " + agree[2] + " neumaier " + agree[3]);
        System.out.println(DoubleStream.of(0.1, 0.2, 0.3).sum());
        System.out.println(DoubleStream.of(1e308, 1e308, -1e308).sum());
        System.out.println(DoubleStream.of(Double.POSITIVE_INFINITY, 1).sum());
        System.out.println(DoubleStream.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).sum());
        System.out.println(DoubleStream.of(Double.NaN, 1).sum());
        System.out.println(DoubleStream.of(Double.MAX_VALUE, Double.MAX_VALUE).sum());
        System.out.println(DoubleStream.of(-0.0, -0.0).sum());
        System.out.println(DoubleStream.of(-0.0).sum());
        System.out.println(DoubleStream.empty().sum());
        System.out.println(DoubleStream.of(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE).sum());
        System.out.println(DoubleStream.of(1, Double.POSITIVE_INFINITY, 2).summaryStatistics());
        System.out.println(DoubleStream.of(-0.0, 0.0).min() + " " + DoubleStream.of(0.0, -0.0).min() + " " + DoubleStream.of(0.0, -0.0).max() + " " + DoubleStream.of(1, Double.NaN).max() + " " + DoubleStream.of(1, Double.NaN).min());
    }
}
