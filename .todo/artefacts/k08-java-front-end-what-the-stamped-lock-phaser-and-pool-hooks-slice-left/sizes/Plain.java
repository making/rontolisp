import java.util.ArrayList;
import java.util.List;

public class Plain {
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
        Object o = mixed.get(0);
        System.out.println(sum(mixed) + " " + (o instanceof Number) + " " + mixed.get(1) + " " + mixed.get(2).equals(4.25));
    }
}
