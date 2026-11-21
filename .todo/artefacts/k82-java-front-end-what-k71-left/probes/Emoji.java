public class Emoji {
    public static void main(String[] args) {
        String s = "a😀b";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c);
        }
        String t = "";
        for (char c : s.toCharArray()) {
            t += c;
        }
        StringBuilder chars = new StringBuilder();
        s.chars().forEach(c -> chars.append((char) c));
        System.out.println(sb + " " + sb.toString().equals(s) + " " + t.equals(s) + " " + chars.toString().equals(s) + " " + sb.length() + " " + t.length() + " " + new StringBuilder(s).reverse());
    }
}
