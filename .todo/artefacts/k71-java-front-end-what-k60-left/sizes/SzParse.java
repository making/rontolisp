public class SzParse {
    public static void main(String[] args) {
        int sum = 0;
        for (String word : "12 7 30".split(" ")) {
            sum += Integer.parseInt(word);
        }
        System.out.println(sum);
    }
}
