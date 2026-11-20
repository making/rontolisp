public class Strip {
  static String show(String s) {
    StringBuilder b = new StringBuilder("[");
    for (char c : s.toCharArray()) {
      if (c == '\n') b.append("\\n"); else if (c == '\r') b.append("\\r"); else if (c == '\t') b.append("\\t");
      else if (c < 0x20 || c > 0x7e) b.append("<").append(Integer.toHexString(c)).append(">"); else b.append(c);
    }
    return b.append("]").toString();
  }
  public static void main(String[] args) {
    String[] texts = {"", " ", "\n", "  a\n  b", "  a\n  b\n", "  a\n    b\n  ", "    a\n  b\n    ", "a  \n\tb\t\n", "\n\n", "  \n  a\n", "  a\r\n  b\r  c", " a\n b", " a\n\n  b\n ", "   x", "x\n", "\t\ta\n\t b\n", "  a\n \n  b", " 　a\n b", "a\u000Bb\n c", "  a  \n  "};
    for (String t : texts) {
      System.out.println(show(t) + " -> " + show(t.stripIndent()));
    }
    String[] escapes = {"a\\tb", "\\b\\t\\n\\f\\r\\s\\\"\\'\\\\", "\\0\\7\\77\\377\\400\\1234\\08\\3x", "line\\\ncont", "x\\\r\ny", "x\\\rz", "\\u0041", "\\q", "\\", "a\\", "\\8", "\\ \\x", "\\9"};
    for (String t : escapes) {
      try { System.out.println(show(t) + " -> " + show(t.translateEscapes())); }
      catch (IllegalArgumentException e) { System.out.println(show(t) + " -> " + e); }
    }
  }
}
