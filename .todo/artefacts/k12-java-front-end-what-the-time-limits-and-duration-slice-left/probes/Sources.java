import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class Sources {
    static void fails(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ran");
        }
        catch (RuntimeException e) {
            System.out.println(what + ": " + e);
        }
    }

    public static void main(String[] args) {
        String[] a = { "a", "b", "c" };
        int[] n = { 1, 2, 3 };
        fails("2,1", () -> Arrays.stream(a, 2, 1));
        fails("-1,2", () -> Arrays.stream(a, -1, 2));
        fails("0,9", () -> Arrays.stream(a, 0, 9));
        fails("4,4", () -> Arrays.stream(a, 4, 4));
        fails("3,3", () -> Arrays.stream(a, 3, 3));
        fails("-1,9", () -> Arrays.stream(a, -1, 9));
        fails("int 2,1", () -> Arrays.stream(n, 2, 1));
        fails("int -1,2", () -> Arrays.stream(n, -1, 2));
        fails("int 0,9", () -> Arrays.stream(n, 0, 9));
        fails("null", () -> Arrays.stream((String[]) null));
        System.out.println(Pattern.compile(",").splitAsStream("").collect(Collectors.toList()));
        System.out.println(Arrays.toString(Pattern.compile(",").split("")));
        System.out.println(Pattern.compile(",").splitAsStream("a,,b,,").collect(Collectors.toList()));
        System.out.println(Pattern.compile(",").splitAsStream(",a").collect(Collectors.toList()));
        System.out.println(Pattern.compile("").splitAsStream("abc").collect(Collectors.toList()));
        System.out.println(Pattern.compile(",").splitAsStream(",,").collect(Collectors.toList()));
        System.out.println(Pattern.compile("x").splitAsStream("abc").collect(Collectors.toList()));
        System.out.println(Arrays.toString(Pattern.compile(",").split(",a")) + Arrays.toString(Pattern.compile(",").split(",,")));
        Matcher m = Pattern.compile("[a-z]+").matcher("ab cd ef gh");
        m.find();
        System.out.println(m.results().map(MatchResult::group).collect(Collectors.toList()));
        System.out.println(m.results().map(r -> r.group() + "@" + r.start()).collect(Collectors.toList()));
        m.reset();
        Stream<MatchResult> rs = m.results();
        m.find();
        System.out.println(rs.map(MatchResult::group).collect(Collectors.toList()));
        fails("after results", () -> System.out.println(m.group()));
        System.out.println("a\nb\r\nc\rd\n\ne\n".lines().map(s -> "[" + s + "]").collect(Collectors.joining()));
        System.out.println("".lines().count() + " " + "\n".lines().count() + " " + "x".lines().count() + " " + "\r\n\r\n".lines().count());
        System.out.println("héllo😀".chars().boxed().collect(Collectors.toList()));
        System.out.println("héllo😀".codePoints().boxed().collect(Collectors.toList()));
        System.out.println(new StringBuilder("abc").chars().sum());
        CharSequence cs = "xyz";
        System.out.println(cs.chars().max().getAsInt() + " " + cs.codePoints().count());
    }
}
