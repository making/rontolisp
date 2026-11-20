import java.util.*;

public class SzRandom {
    public static void main(String[] args) {
        Random r = new Random(5);
        System.out.println(r.nextInt(10) + " " + r.nextDouble() + " " + r.doubles(3, 0, 1).sum());
    }
}
