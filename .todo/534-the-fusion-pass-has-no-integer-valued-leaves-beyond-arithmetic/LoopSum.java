public class LoopSum {
	public static void main(String[] args) {
		long s = 0;
		for (long i = 1; i <= 100000000L; i++) {
			s += i;
		}
		System.out.println(s);
	}
}
