public class NthSumBoxedCons {

	record Cons(Long car, Cons cdr) {
	}

	static Long nth(int n, Cons l) {
		for (int i = 0; i < n; i++) {
			l = l.cdr();
		}
		return l.car();
	}

	public static void main(String[] args) {
		Cons lst = null;
		for (long i = 1000; i >= 1; i--) {
			lst = new Cons(i, lst);
		}
		Long s = 0L;
		for (int i = 0; i < 1000000; i++) {
			s += nth(999, lst);
		}
		System.out.println(s);
	}
}
