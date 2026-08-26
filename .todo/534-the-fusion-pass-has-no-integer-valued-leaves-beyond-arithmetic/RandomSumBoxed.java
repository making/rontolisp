import java.util.Random;

public class RandomSumBoxed {
	public static void main(String[] args) {
		Random r = new Random();
		Long s = 0L;
		for (int i = 0; i < 10000000; i++) {
			s += r.nextInt(1000000);
		}
		System.out.println(s);
	}
}
