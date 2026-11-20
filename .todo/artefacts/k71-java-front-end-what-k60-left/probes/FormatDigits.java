public class FormatDigits {
  public static void main(String[] args) {
    String[] controls = {"%٣d|", "%1$٣d|", "%٣$d|", "%.٢f|", "%３d|"};
    for (String c : controls) {
      try { System.out.println(c + " -> " + String.format(c, 5, 6.25)); } catch (RuntimeException e) { System.out.println(c + " -> " + e); }
    }
  }
}
