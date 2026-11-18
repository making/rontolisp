public class Throws {
    public static void main(String[] args) {
        try {
            throw new IllegalStateException("boom");
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
        }
    }
}
