import java.util.*;
import java.util.concurrent.*;

public class SzQueue {
    public static void main(String[] args) {
        Queue<String> q = new ConcurrentLinkedQueue<>(List.of("a", "b"));
        for (String s : q) {
            System.out.println(s);
        }
    }
}
