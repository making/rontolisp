import java.util.concurrent.ThreadLocalRandom;

public class RandomSum {
	public static void main(String[] args) {
		long s = 0;
		for (int i = 0; i < 10000000; i++) {
			s += ThreadLocalRandom.current().nextInt(1000000);
		}
		System.out.println(s);
	}
}
