public class CharLoop {
    public static void main(String[] args) {
        String s = "abcdefghijklmnopqrstuvwxyz";
        int sum = 0;
        for (int round = 0; round < 1000000; round++) {
            for (int i = 0; i < s.length(); i++) sum += s.charAt(i);
        }
        System.out.println(sum);
    }
}
