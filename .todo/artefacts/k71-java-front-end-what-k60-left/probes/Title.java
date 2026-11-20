public class Title {
  public static void main(String[] args) {
    int n = 0;
    StringBuilder sb = new StringBuilder();
    for (int c = 0; c <= 0x10FFFF; c++) {
      int t = Character.toTitleCase(c), u = Character.toUpperCase(c);
      if (t != u) { n++; sb.append(String.format("%X->%X(u %X) ", c, t, u)); }
    }
    System.out.println(n + ": " + sb);
    // isTitleCase chars
    StringBuilder tc = new StringBuilder();
    for (int c = 0; c <= 0x10FFFF; c++) if (Character.isTitleCase(c)) tc.append(String.format("%X ", c));
    System.out.println(tc);
    try { Character.codePointAt("abc", 3); } catch (RuntimeException e) { System.out.println(e); }
    try { Character.codePointAt("abc", -1); } catch (RuntimeException e) { System.out.println(e); }
    try { Character.codePointAt((CharSequence) null, 0); } catch (RuntimeException e) { System.out.println(e); }
    try { Character.codePointCount("abc", 2, 1); } catch (RuntimeException e) { System.out.println(e); }
    try { Character.codePointCount("abc", -1, 1); } catch (RuntimeException e) { System.out.println(e); }
    try { Character.codePointCount("abc", 0, 4); } catch (RuntimeException e) { System.out.println(e); }
    System.out.println(Character.codePointAt(new StringBuilder("a😀b"), 1) + " " + Character.codePointCount(new StringBuilder("a😀b"), 0, 4) + " " + Character.codePointAt("xy", 1) + " " + Character.codePointCount("hello", 1, 3));
    StringBuilder a = new StringBuilder("abc"), b = new StringBuilder("abd");
    System.out.println(a.compareTo(b) + " " + b.compareTo(a) + " " + a.compareTo(new StringBuilder("abc")) + " " + a.compareTo(new StringBuilder("ab")) + " " + new StringBuilder("B").compareTo(new StringBuilder("a")) + " " + new StringBuffer("x").compareTo(new StringBuffer("xyz")));
  }
}
