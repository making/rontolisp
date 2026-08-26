import java.util.concurrent.ThreadLocalRandom;

public class ArefSum {
	public static void main(String[] args) {
		long[] arr = new long[1000000];
		for (int i = 0; i < 1000000; i++) {
			arr[i] = i + 1;
		}
		long s = 0;
		for (int i = 0; i < 10000000; i++) {
			s += arr[ThreadLocalRandom.current().nextInt(1000000)];
		}
		System.out.println(s);
	}
}
