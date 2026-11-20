package lib;
import java.util.*;
public class Lib {
    public static String all() {
        List<String> out = new ArrayList<>();
        for (Greeter g : ServiceLoader.load(Greeter.class)) out.add(g.greet("x"));
        return out.toString();
    }
}
