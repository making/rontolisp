import java.util.*;
import java.util.stream.*;

public class MultiOnly {
    public static void main(String[] args) {
        System.out.println(List.of(1, 2, 3).stream().<Integer>mapMulti((x, to) -> { to.accept(x); to.accept(-x); }).toList());
    }
}
