public class Letters {
    public static void main(String[] args) {
        String text = "Hello, World 42 times";
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                letters++;
            }
        }
        System.out.println(letters);
    }
}
