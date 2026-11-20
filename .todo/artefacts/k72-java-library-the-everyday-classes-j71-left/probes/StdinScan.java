import java.util.*;

// A Scanner over System.in: numbers, words and lines as a program of a contest reads them.
public class StdinScan {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int n = in.nextInt();
        long sum = 0;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = in.nextInt();
            sum += a[i];
        }
        System.out.println("n=" + n + " sum=" + sum + " last=" + a[n - 1]);
        System.out.println("rest of line [" + in.nextLine() + "]");
        System.out.println("line [" + in.nextLine() + "]");
        String word = in.next();
        double d = in.nextDouble();
        boolean b = in.nextBoolean();
        System.out.println(word + " " + d + " " + b + " " + in.hasNextInt() + " " + in.hasNext());
        System.out.println(in.next() + " " + in.findInLine("\\d+") + " [" + in.nextLine() + "]");
        while (in.hasNextLine()) {
            String line = in.nextLine();
            System.out.println("[" + line + "] " + line.length());
        }
        System.out.println(in.hasNext() + " " + in.ioException());
        in.close();
        Scanner again = new Scanner(System.in);
        System.out.println("System.in closed: " + again.hasNext() + " " + again.hasNextLine());
    }
}
