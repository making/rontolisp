class Cell { int value; Cell next; Cell(int value, Cell next) { this.value = value; this.next = next; } }
public class ListWalk {
    public static void main(String[] args) {
        Cell head = null;
        for (int i = 0; i < 1000; i++) head = new Cell(i, head);
        long sum = 0;
        for (int round = 0; round < 200000; round++) {
            for (Cell p = head; p != null; p = p.next) sum += p.value;
        }
        System.out.println(sum);
    }
}
