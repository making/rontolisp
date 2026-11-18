public class Nest {
    static class Point { int x; Point(int x) { this.x = x; } }
    public static void main(String[] args) {
        int total = 0;
        for (int i = 0; i < 10; i++) total += new Point(i).x;
        System.out.println(total);
    }
}
