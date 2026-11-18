import java.util.ArrayList;
import java.util.List;

public class ListAdd {
    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(i);
        }
        int sum = 0;
        for (int x : list) {
            sum += x;
        }
        System.out.println(sum + " " + list);
    }
}
