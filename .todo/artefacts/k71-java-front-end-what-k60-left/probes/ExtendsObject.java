import java.util.*;
public class ExtendsObject {
  static void v(List<? extends Object>... xs) {}
  public static void main(String[] args) {
    Object obj = new ArrayList<>();
    List<? extends Object> a = (List<? extends Object>) obj;
    Object[] arr = new List<? extends Object>[1];
    v(new ArrayList<String>());
    boolean b = obj instanceof List<? extends Object>;
    System.out.println(a + " " + arr.length + " " + b);
  }
}
