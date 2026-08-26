public class NthSmall {

	record Cons(long car, Cons cdr) {
	}

	static long nth(int n, Cons l) {
		for (int i = 0; i < n; i++) {
			l = l.cdr();
		}
		return l.car();
	}

	public static void main(String[] args) {
		Cons lst = null;
		for (long i = 100; i >= 1; i--) {
			lst = new Cons(i, lst);
		}
		long s = 0;
		for (int i = 0; i < 10000000; i++) {
			s += nth(99, lst);
		}
		System.out.println(s);
	}
}
