import java.util.*;

public class Enums4 {
    enum Day { MON, TUE }
    enum Color { RED }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) {
        try {
            ((Set) EnumSet.noneOf(Day.class)).add(Color.RED);
        } catch (RuntimeException e) {
            System.out.println(e);
        }
        try {
            ((Map) new EnumMap<Day, String>(Day.class)).put(Color.RED, "x");
        } catch (RuntimeException e) {
            System.out.println(e);
        }
        try {
            ((Set) EnumSet.noneOf(Day.class)).add("x");
        } catch (RuntimeException e) {
            System.out.println(e);
        }
        try {
            ((Map) new EnumMap<Day, String>(Day.class)).put("x", "x");
        } catch (RuntimeException e) {
            System.out.println(e);
        }
    }
}
