public class SbBase {
    public static void main(String[] args) {
        StringBuilder b = new StringBuilder("hello").insert(1, "xy").append(new char[] {'a', 'b'}, 0, 1);
        char[] into = new char[3];
        "abc".getChars(0, 3, into, 0);
        StringBuffer f = new StringBuffer("q").insert(0, 1);
        System.out.println(b + " " + new String(into) + " " + f + " " + Boolean.parseBoolean("TRUE") + " " + System.getenv("HOME_NOT_THERE"));
    }
}
