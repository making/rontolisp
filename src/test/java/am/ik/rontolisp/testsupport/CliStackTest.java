package am.ik.rontolisp.testsupport;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(CliStackExtension.class)
class CliStackTest {

	// Deep enough to finish quickly and show real headroom on a 16 MiB thread
	// (CliStack.BYTES or SizedThread.WORKER_STACK_BYTES); not used to prove an overflow.
	private static final int DEPTH = 80_000;

	private static final long DEFAULT_STACK_BYTES = 1L << 20;

	@Test
	void aTestMethodRunsOnTheCliStack() throws Exception {
		// The pairing, not a margin: the depth used on both sides is whatever ACTUALLY
		// overflows a default-sized thread on this run, found by doubling until it does,
		// not an assumed frame size. `depth` can already be JIT-compiled by the time this
		// method runs (other tests in the class call it first), and a compiled frame is
		// smaller than an interpreted one, so a fixed constant picked once can stop
		// overflowing a 1 MiB thread once the suite runs warm (.kb/interpreter-stack.md,
		// "The numbers").
		int[] overflowDepth = new int[1];
		Throwable[] control = new Throwable[1];
		Thread thread = new Thread(null, () -> {
			int n = 1;
			Throwable thrown = null;
			while (!(thrown instanceof StackOverflowError)) {
				n *= 2;
				int attempt = n;
				thrown = catchThrowable(() -> depth(attempt));
			}
			overflowDepth[0] = n;
			control[0] = thrown;
		}, "control", DEFAULT_STACK_BYTES);
		thread.start();
		thread.join();
		assertThat(control[0]).isInstanceOf(StackOverflowError.class);

		assertThat(Thread.currentThread().getName()).isEqualTo("aTestMethodRunsOnTheCliStack()");
		assertThat(depth(overflowDepth[0])).isEqualTo(overflowDepth[0]);
	}

	@Test
	void callAnswersTheBodysValueFromAThreadOfItsOwn() throws Exception {
		Thread caller = Thread.currentThread();
		assertThat(CliStack.call("probe", () -> Thread.currentThread() != caller ? depth(DEPTH) : -1)).isEqualTo(DEPTH);
	}

	@Test
	void callRethrowsWhatTheBodyThrewAsItself() {
		assertThatThrownBy(() -> CliStack.call("probe", () -> {
			throw new IOException("checked");
		})).isExactlyInstanceOf(IOException.class).hasMessage("checked");
		assertThatThrownBy(() -> CliStack.call("probe", () -> {
			throw new AssertionError("failed");
		})).isExactlyInstanceOf(AssertionError.class).hasMessage("failed");
	}

	@Test
	void callWithinAbandonsABodyThatOutlivesTheTimeout() throws Exception {
		CountDownLatch never = new CountDownLatch(1);
		assertThat(CliStack.callWithin("probe", () -> {
			never.await();
			return "done";
		}, Duration.ofMillis(50))).isEmpty();
		assertThat(CliStack.callWithin("probe", () -> "done", Duration.ofMinutes(1))).contains("done");
	}

	private static int depth(int n) {
		return n == 0 ? 0 : 1 + depth(n - 1);
	}

}
