import java.util.*;

public class RawMax {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args) {
        List raw = new ArrayList(List.of(3, 1, 2));
        Object m = Collections.max(raw);
        System.out.println(m + " " + Collections.min(raw));
        Collections.sort(raw);
        System.out.println(raw);
    }
}
