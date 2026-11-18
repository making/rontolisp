class Table {
    static int[] squares = new int[10];

    static {
        for (int i = 0; i < 10; i++) {
            squares[i] = i * i;
        }
        System.out.println("table");
    }

    static int at(int i) {
        return squares[i];
    }
}

public class Lazy {
    public static void main(String[] args) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += Table.at(i);
        }
        System.out.println(sum);
    }
}
