public class LettersLoop {
    public static void main(String[] args) {
        String text = "Hello, World 42 times é中";
        int letters = 0;
        for (int n = 0; n < 500000; n++) {
            for (int i = 0; i < text.length(); i++) {
                if (Character.isLetter(text.charAt(i))) {
                    letters++;
                }
            }
        }
        System.out.println(letters);
    }
}
