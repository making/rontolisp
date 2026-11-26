import java.util.Locale;
public class LocBase {
    public static void main(String[] args) {
        Locale l = Locale.getDefault();
        System.out.println(l + " " + String.format(Locale.US, "%,d", 1234567) + " " + "abc".toUpperCase(Locale.ROOT));
    }
}
