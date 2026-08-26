import java.util.stream.LongStream;

public class LoopSumStream {
	public static void main(String[] args) {
		System.out.println(LongStream.rangeClosed(1, 100000000L).sum());
	}
}
