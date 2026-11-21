import java.util.regex.*;
public class PatOnly {
    public static void main(String[] args) {
        Matcher m = Pattern.compile(",").matcher("a,b,c");
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        m.region(1, 5);
        while (m.find()) {
            System.out.println(m.start() + " " + m.hitEnd() + " " + m.requireEnd());
        }
        System.out.println(m.lookingAt() + " " + m.matches() + " " + m.toMatchResult().group());
    }
}
