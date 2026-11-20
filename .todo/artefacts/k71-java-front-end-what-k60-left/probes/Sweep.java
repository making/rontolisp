public class Sweep {
  public static void main(String[] args) {
    long h1 = 0, h2 = 0, h3 = 0;
    int digits = 0;
    for (int c = 0; c <= 0x10FFFF; c++) {
      int d = Character.digit(c, 36);
      if (d >= 0) digits++;
      h1 = h1 * 31 + d + 2;
      h2 = h2 * 31 + Character.digit(c, 10) + 2;
      h3 = h3 * 31 + Character.toTitleCase(c);
    }
    System.out.println(digits + " " + h1 + " " + h2 + " " + h3);
  }
}
