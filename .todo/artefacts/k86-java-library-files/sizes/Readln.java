public class Readln {
    public static void main(String[] args) {
        String line;
        int n = 0;
        while ((line = IO.readln()) != null) {
            n += line.length();
        }
        System.out.println(n);
    }
}
