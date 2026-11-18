class Table {
    static int[] squares;
    static boolean done;
    static boolean failed;

    static void init() {
        if (done) {
            return;
        }
        done = true;
        boolean ok = false;
        try {
            squares = new int[10];
            for (int i = 0; i < 10; i++) {
                squares[i] = i * i;
            }
            System.out.println("table");
            ok = true;
        } finally {
            if (!ok) {
                failed = true;
            }
        }
    }

    static int at(int i) {
        init();
        return squares[i];
    }
}

public class Marked {
    public static void main(String[] args) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += Table.at(i);
        }
        System.out.println(sum);
    }
}
