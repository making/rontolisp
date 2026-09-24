package am.ik.rontolisp.testsupport;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadStdioTest {

	@Test
	void twoThreadsCaptureTheirOwnOutputThroughTheSameSystemOut() throws Exception {
		// Both threads print between the same two barrier trips, so the writes interleave
		// in time; each capture must still hold exactly its own thread's lines.
		CyclicBarrier barrier = new CyclicBarrier(2);
		ByteArrayOutputStream[] captured = { new ByteArrayOutputStream(), new ByteArrayOutputStream() };
		Thread[] threads = new Thread[2];
		for (int i = 0; i < 2; i++) {
			int id = i;
			threads[i] = new Thread(() -> {
				try (var _ = ThreadStdio.out(captured[id])) {
					barrier.await();
					for (int k = 0; k < 1000; k++) {
						System.out.println("t" + id);
					}
					barrier.await();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});
			threads[i].start();
		}
		for (Thread thread : threads) {
			thread.join();
		}
		assertThat(captured[0].toString()).isEqualTo(("t0" + System.lineSeparator()).repeat(1000));
		assertThat(captured[1].toString()).isEqualTo(("t1" + System.lineSeparator()).repeat(1000));
	}

	@Test
	void aThreadStartedInsideTheScopeWritesWhereItsCreatorWrites() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.out(out)) {
			Thread child = new Thread(() -> System.out.print("child"));
			child.start();
			child.join();
		}
		assertThat(out.toString()).isEqualTo("child");
	}

	@Test
	void closingAnInnerScopeRestoresTheOuterTarget() {
		ByteArrayOutputStream outer = new ByteArrayOutputStream();
		ByteArrayOutputStream inner = new ByteArrayOutputStream();
		try (var _ = ThreadStdio.err(outer)) {
			System.err.print("a");
			try (var _ = ThreadStdio.err(inner)) {
				System.err.print("b");
			}
			System.err.print("c");
		}
		assertThat(outer.toString()).isEqualTo("ac");
		assertThat(inner.toString()).isEqualTo("b");
	}

	@Test
	void readsComeFromThisThreadsSource() throws Exception {
		try (var _ = ThreadStdio.in(new ByteArrayInputStream("line\n".getBytes(StandardCharsets.UTF_8)))) {
			assertThat(new BufferedReader(new InputStreamReader(System.in)).readLine()).isEqualTo("line");
		}
	}

}
