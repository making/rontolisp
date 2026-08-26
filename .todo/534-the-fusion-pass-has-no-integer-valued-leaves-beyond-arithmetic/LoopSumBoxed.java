import java.util.stream.LongStream;

public class LoopSumBoxed {
	public static void main(String[] args) {
		System.out.println(LongStream.rangeClosed(1, 100000000L).boxed().reduce(0L, Long::sum));
	}
}
