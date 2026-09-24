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

	// Deep enough to overflow the JVM's default thread stack (1 MiB on linux-x64) even
	// with compiled frames, shallow enough for 16 MiB with interpreted ones.
	private static final int DEPTH = 80_000;

	private static final long DEFAULT_STACK_BYTES = 1L << 20;

	@Test
	void aTestMethodRunsOnTheCliStack() throws Exception {
		// The pairing, not a margin: the same recursion must overflow a default-sized
		// thread, or passing here would not show the method had more.
		Throwable[] control = new Throwable[1];
		Thread thread = new Thread(null, () -> control[0] = catchThrowable(() -> depth(DEPTH)), "control",
				DEFAULT_STACK_BYTES);
		thread.start();
		thread.join();
		assertThat(control[0]).isInstanceOf(StackOverflowError.class);

		assertThat(Thread.currentThread().getName()).isEqualTo("aTestMethodRunsOnTheCliStack()");
		assertThat(depth(DEPTH)).isEqualTo(DEPTH);
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
