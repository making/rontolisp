public class Format {
    public static void main(String[] args) {
        System.out.println(String.format("%5d|%-8s|%.2f", 42, "ab", 3.14159));
        System.out.printf("%s %d%n", "x", 1);
    }
}
