import java.util.StringJoiner;
public class JoinOnly {
    public static void main(String[] args) {
        StringJoiner j = new StringJoiner(", ", "[", "]");
        for (String a : args) {
            j.add(a);
        }
        System.out.println(j.add("x"));
    }
}
