public class PrintHalves {
    public static void main(String[] args) {
        String s = "a😀b";
        s.chars().forEach(c -> System.out.print((char) c));
        System.out.println();
        for (char c : s.toCharArray()) {
            System.out.print(c);
        }
        System.out.println();
        System.out.print((char) 0xD83D);
        System.out.println("x");
        System.out.print(String.valueOf((char) 0xD83D));
        System.out.print(String.valueOf((char) 0xDE00));
        System.out.println();
    }
}
