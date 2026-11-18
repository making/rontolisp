public class Halves {
    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append((char) 0xd83d).append((char) 0xde00);
        String joined = sb.toString();
        String whole = new String(new char[] {0xd83d, 0xde00});
        System.out.println(joined.equals(whole) + " " + joined.length() + " " + whole.length() + " " + joined.toCharArray().length + " " + (joined + "!").length() + " " + joined.hashCode() + " " + whole.hashCode() + " " + Character.digit('٣', 10) + " " + Character.getNumericValue('Ⅷ') + " " + Integer.parseInt("٣٤"));
    }
}
