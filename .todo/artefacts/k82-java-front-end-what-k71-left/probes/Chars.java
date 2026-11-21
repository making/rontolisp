public class Chars {
    public static void main(String[] args) {
        String s = "a😀b";
        StringBuilder out = new StringBuilder();
        s.chars().forEach(c -> out.append(Integer.toHexString(c)).append(' '));
        StringBuilder chars = new StringBuilder();
        s.chars().forEach(c -> chars.append((char) c));
        StringBuilder points = new StringBuilder();
        s.codePoints().forEach(c -> points.appendCodePoint(c));
        char[] units = s.toCharArray();
        System.out.println(out + "| " + s.chars().count() + " " + units.length + " " + chars.length() + " " + chars.toString().length() + " " + chars.toString().equals(s) + " " + points.toString().equals(s) + " " + (int) chars.charAt(1) + " " + chars.toString().codePointAt(1) + " " + new String(units).equals(s) + " " + String.valueOf(units, 1, 2).length());
    }
}
