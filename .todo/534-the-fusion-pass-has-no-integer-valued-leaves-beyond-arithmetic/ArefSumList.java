import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ArefSumList {
	public static void main(String[] args) {
		List<Long> arr = new ArrayList<>();
		for (int i = 0; i < 1000000; i++) {
			arr.add((long) (i + 1));
		}
		Random r = new Random();
		Long s = 0L;
		for (int i = 0; i < 10000000; i++) {
			s += arr.get(r.nextInt(1000000));
		}
		System.out.println(s);
	}
}
