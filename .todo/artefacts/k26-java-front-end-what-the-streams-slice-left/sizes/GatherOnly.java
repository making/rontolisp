import java.util.*;
import java.util.stream.*;

public class GatherOnly {
    public static void main(String[] args) {
        System.out.println(List.of(1, 2, 3, 4, 5).stream().gather(Gatherers.windowFixed(2)).toList());
    }
}
