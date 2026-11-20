import java.util.*;
public class NumericValues {
  public static void main(String[] args) {
    List<int[]> runs = new ArrayList<>(); // from, to, value, step
    for (int c = 0; c <= 0x10FFFF; c++) {
      boolean isNd = Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER;
      boolean rule = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0xFF21 && c <= 0xFF3A) || (c >= 0xFF41 && c <= 0xFF5A) || isNd;
      int v = Character.getNumericValue(c);
      if (v == -1 || rule) continue;
      int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
      if (last != null && last[1] == c - 1) {
        int lastVal = last[2] + (last[1] - last[0]) * last[3];
        if (last[1] == last[0]) { // a run of one: step free
          if (v == lastVal + 1) { last[3] = 1; last[1] = c; continue; }
          if (v == lastVal) { last[3] = 0; last[1] = c; continue; }
        } else if (v == lastVal + last[3]) { last[1] = c; continue; }
      }
      runs.add(new int[] {c, c, v, 0});
    }
    System.out.println("runs " + runs.size());
    int maxv = 0; int big = 0;
    StringBuilder sb = new StringBuilder();
    for (int[] r : runs) { maxv = Math.max(maxv, r[2]); if (r[2] > 1000) big++; }
    System.out.println("max " + maxv + " big " + big);
    // encoding estimate: delta from previous end, length, value, step
    int prevEnd = 0; int chars = 0;
    for (int[] r : runs) {
      String s = (r[0] - prevEnd) + " " + (r[1] - r[0]) + " " + r[2] + (r[3] == 1 ? "+" : "") + " ";
      chars += s.length(); prevEnd = r[1];
      sb.append(s);
    }
    System.out.println("chars " + chars);
    System.out.println(sb.substring(0, 600));
  }
}
