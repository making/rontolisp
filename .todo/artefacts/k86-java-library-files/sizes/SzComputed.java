public class SzComputed {
    public static void main(String[] args) {
        String control = args.length > 0 ? args[0] : "%5d|%-4s";
        System.out.println(String.format(control, 42, "ab"));
    }
}
