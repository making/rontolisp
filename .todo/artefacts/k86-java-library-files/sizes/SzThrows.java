public class SzThrows {
    public static void main(String[] args) {
        try {
            System.out.println(Integer.parseInt(args.length > 0 ? args[0] : "x"));
        } catch (NumberFormatException e) {
            System.out.println(e.getMessage());
        }
    }
}
