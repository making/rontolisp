import java.util.ArrayList;
import java.util.List;

// Plain with one class of the program under Number.
public class Under {
    static final class Half extends Number {
        public int intValue() {
            return 0;
        }

        public long longValue() {
            return 0;
        }

        public float floatValue() {
            return 0.5f;
        }

        public double doubleValue() {
            return 0.5;
        }
    }

    static double sum(List<? extends Number> numbers) {
        double sum = 0;
        for (Number number : numbers) {
            sum += number.doubleValue() + number.byteValue();
        }
        return sum;
    }

    public static void main(String[] args) {
        List<Number> mixed = new ArrayList<>();
        mixed.add(1);
        mixed.add(2L);
        mixed.add(4.25);
        mixed.add(new Half());
        Object o = mixed.get(0);
        System.out.println(sum(mixed) + " " + (o instanceof Number) + " " + mixed.get(1) + " " + mixed.get(2).equals(4.25));
    }
}
