import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class Lone {
    public static void main(String[] args) {
        char hi = (char) 0xd83d;
        String lone = String.valueOf(hi);
        String constant = "x\ud83dy";
        System.out.println(Arrays.toString(lone.getBytes(StandardCharsets.UTF_8)) + " " + Arrays.toString(constant.getBytes(StandardCharsets.UTF_8)) + " " + constant.length() + " " + (int) constant.charAt(1) + " " + constant.equals("x" + hi + "y"));
        System.out.println(lone);
        System.out.println("[" + constant + "]");
        String low = String.valueOf((char) 0xde00);
        String joined = lone + low;
        System.out.println(joined.equals("😀") + " " + joined.codePointCount(0, joined.length()) + " " + joined.hashCode() + " " + lone.concat(low).equals(joined) + " " + String.join("", lone, low).equals(joined));
    }
}
