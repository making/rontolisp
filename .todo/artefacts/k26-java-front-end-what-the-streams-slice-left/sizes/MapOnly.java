import java.util.*;
import java.util.stream.*;

public class MapOnly {
    public static void main(String[] args) {
        System.out.println(List.of(1, 2, 3).stream().map(x -> -x).toList());
    }
}
