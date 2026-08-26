import java.util.LinkedList;
import java.util.List;

public class NthSumLinked {
	public static void main(String[] args) {
		List<Long> lst = new LinkedList<>();
		for (long i = 1; i <= 1000; i++) {
			lst.add(i);
		}
		Long s = 0L;
		for (int i = 0; i < 1000000; i++) {
			s += lst.get(999);
		}
		System.out.println(s);
	}
}
