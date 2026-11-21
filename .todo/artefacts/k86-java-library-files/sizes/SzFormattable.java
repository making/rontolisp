import java.util.Formattable;
import java.util.Formatter;

public class SzFormattable {
    record Point(int x, int y) implements Formattable {
        public void formatTo(Formatter f, int flags, int width, int precision) {
            f.format("(%d, %d)", x, y);
        }
    }

    public static void main(String[] args) {
        System.out.println(String.format("%s", new Point(1, 2)));
    }
}
