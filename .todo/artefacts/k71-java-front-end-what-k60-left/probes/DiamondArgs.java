import java.util.*;
public class DiamondArgs {
  static class A<T> { A(Collection<? extends T> c) {} }
  static class B<T> { B(List<? super T> c) {} }
  static class C<T> { C(Map<T, ?> c) {} }
  static class D<T> { D(List<?> c, T t) {} }
  static class E<T> { E(List<T> c, T t) {} }
  static class F<T> { <U> F(List<U> c) {} }
  static class G<T> { G(List<String> c) {} }
  static class H { H(List<String> c) {} }
  static class I<T> { I(Collection<? extends T> c) {} }
  public static void main(String[] args) {
    List raw = new ArrayList(List.of(3, 1, 2));
    Map rawMap = new HashMap();
    Object a = new A<>(raw);
    Object b = new B<>(raw);
    Object c = new C<>(rawMap);
    Object d = new D<>(raw, 1);
    Object e = new E<>(raw, 1);
    Object f = new F<>(raw);
    Object g = new G<>(raw);
    Object h = new H(raw);
    Object i = new I<String>(raw);
  }
}
