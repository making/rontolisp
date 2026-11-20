import java.util.*;
import java.util.function.*;

public class Rand2 {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        attempt("nextDouble(0, inf)", () -> new Random(1).nextDouble(0, Double.POSITIVE_INFINITY));
        attempt("nextDouble(NaN, 1)", () -> new Random(1).nextDouble(Double.NaN, 1));
        attempt("nextDouble(0, NaN)", () -> new Random(1).nextDouble(0, Double.NaN));
        attempt("nextFloat(NaN)", () -> new Random(1).nextFloat(Float.NaN));
        attempt("nextFloat(inf)", () -> new Random(1).nextFloat(Float.POSITIVE_INFINITY));
        attempt("nextFloat(-inf, 0)", () -> new Random(1).nextFloat(Float.NEGATIVE_INFINITY, 0f));
        attempt("nextFloat(0, inf)", () -> new Random(1).nextFloat(0f, Float.POSITIVE_INFINITY));
        attempt("nextFloat(1, 1)", () -> new Random(1).nextFloat(1f, 1f));
        attempt("nextDouble(-0.0, 0.0)", () -> new Random(1).nextDouble(-0.0, 0.0));
        attempt("nextDouble(min, 2 min)", () -> new Random(1).nextDouble(Double.MIN_VALUE, 2 * Double.MIN_VALUE));
        attempt("nextInt(MIN, MIN + 1)", () -> new Random(1).nextInt(Integer.MIN_VALUE, Integer.MIN_VALUE + 1));
        attempt("nextLong(1)", () -> new Random(1).nextLong(1));
    }
}
