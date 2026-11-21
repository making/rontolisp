import java.util.Scanner;
public class ScanDelim {
    public static void main(String[] args) {
        Scanner in = new Scanner("a,b,c").useDelimiter(",");
        while (in.hasNext()) {
            System.out.println(in.next());
        }
    }
}
