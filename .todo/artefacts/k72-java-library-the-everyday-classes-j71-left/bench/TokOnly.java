import java.util.StringTokenizer;
public class TokOnly {
    public static void main(String[] args) {
        StringTokenizer t = new StringTokenizer("a b c");
        while (t.hasMoreTokens()) {
            System.out.println(t.nextToken());
        }
    }
}
