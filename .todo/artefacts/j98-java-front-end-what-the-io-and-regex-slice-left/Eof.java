public class Eof {
    public static void main(String[] args) {
        System.out.println("got " + IO.readln());
        System.out.println("got " + IO.readln() + ".");
        String line = IO.readln();
        System.out.println(line == null);
    }
}
