import java.math.*;
public class Digits {
  static void p(String what, Object v) { System.out.println(what + " = " + v); }
  static void tryParse(String s) {
    try { p("parseInt(" + s + ")", Integer.parseInt(s)); } catch (NumberFormatException e) { p("parseInt(" + s + ")", e.getMessage()); }
    try { p("parseLong(" + s + ")", Long.parseLong(s, 16)); } catch (NumberFormatException e) { p("parseLong16(" + s + ")", e.getMessage()); }
    try { p("parseDouble(" + s + ")", Double.parseDouble(s)); } catch (NumberFormatException e) { p("parseDouble(" + s + ")", e.getMessage()); }
    try { p("BigInteger(" + s + ")", new BigInteger(s)); } catch (NumberFormatException e) { p("BigInteger(" + s + ")", e.getMessage()); }
    try { p("BigDecimal(" + s + ")", new BigDecimal(s)); } catch (NumberFormatException e) { p("BigDecimal(" + s + ")", e.getMessage()); }
  }
  public static void main(String[] args) {
    int[] codes = {'7', 'z', 'Z', 0x663, 0x6F5, 0x96B, 0xFF13, 0xFF21, 0xFF5A, 0x1D7D9, 0x1D7FF, 0x2167, 0xB2, 0xBD, 0x2460, 0x3007, 0x10107, 0x5146, 0x1FBF9, 0x1FBFA, 0x0F33};
    for (int c : codes) {
      p(Integer.toHexString(c), Character.digit(c, 10) + " " + Character.digit(c, 16) + " " + Character.digit(c, 36) + " " + Character.getNumericValue(c) + " " + Character.isDigit(c));
      if (c < 0x10000) p(Integer.toHexString(c) + " char", Character.digit((char) c, 10) + " " + Character.getNumericValue((char) c));
    }
    tryParse("٣٤");
    tryParse("１２");
    tryParse("1٣.5");
    tryParse("-१२");
  }
}
