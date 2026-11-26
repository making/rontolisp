import java.util.*;
import java.util.function.Function;
import java.util.stream.*;
public class CollBase {
    enum E { A, B }
    public static void main(String[] args) {
        Map<String, E> m = Arrays.stream(E.values()).collect(Collectors.toMap(E::name, Function.identity()));
        List<String> l = Stream.of("x", "y").collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
        System.out.println(new TreeMap<>(m) + " " + l);
    }
}
