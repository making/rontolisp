import java.util.*;

public class SzEnumSet {
    enum Day { MON, TUE, WED, THU, FRI }

    public static void main(String[] args) {
        EnumSet<Day> s = EnumSet.of(Day.MON, Day.WED);
        s.add(Day.FRI);
        for (Day d : s) {
            System.out.println(d);
        }
        System.out.println(s.contains(Day.TUE) + " " + EnumSet.complementOf(s));
    }
}
