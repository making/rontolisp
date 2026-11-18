import java.util.HashMap;
import java.util.Map;

public class MapPut {
    public static void main(String[] args) {
        Map<String, Integer> map = new HashMap<>();
        map.put("a", 1);
        map.put("b", 2);
        System.out.println(map.get("a") + " " + map);
    }
}
