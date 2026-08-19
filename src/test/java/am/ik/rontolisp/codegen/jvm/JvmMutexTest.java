package am.ik.rontolisp.codegen.jvm;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mutex primitives on the JVM backend. The property under test is the one the
 * interpreter's {@code MutexTest} pins and the one a served handler depends on:
 * concurrent increments of a shared global under the lock agree with the sequential
 * result. The threads here are Java's, invoking the compiled {@code defun} directly --
 * exactly what {@code rontolisp:serve} does per request (Lisp-spawned threads are
 * {@code JvmThreadTest}'s subject).
 */
class JvmMutexTest {

	@TempDir
	Path tempDir;

	@Test
	void concurrentIncrementsUnderTheLockAgreeWithTheSequentialResult() throws Exception {
		int threads = 4;
		int perThread = 2000;
		// OptimizeLevel.NONE: the threads below invoke MT-BUMP/MT-REPORT REFLECTIVELY,
		// an edge no bytecode shows, and the JVM class shaker roots at main -- so a
		// shaken class would drop both methods. The property under test is the mutex,
		// not the shaker's root set.
		JvmLispCompiler compiler = new JvmLispCompiler("MutexProg", false, OptimizeLevel.NONE);
		byte[] classBytes = compiler.compile(LispReader.readAllFromString("""
				(defvar *mt-lock* (rontolisp:make-mutex))
				(defvar *mt-counter* 0)
				(defun mt-bump (n)
				  (dotimes (i n)
				    (rontolisp:with-mutex (*mt-lock*)
				      (setq *mt-counter* (+ *mt-counter* 1)))))
				(defun mt-report () *mt-counter*)
				"""));
		Files.write(this.tempDir.resolve("MutexProg.class"), classBytes);
		try (URLClassLoader loader = new URLClassLoader(new URL[] { this.tempDir.toUri().toURL() },
				ClassLoader.getSystemClassLoader())) {
			Class<?> clazz = loader.loadClass("MutexProg");
			// Run the top level once: it creates the lock and zeroes the counter.
			clazz.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
			Method bump = clazz.getDeclaredMethod("MT-BUMP", Object.class);
			bump.setAccessible(true);
			Method report = clazz.getDeclaredMethod("MT-REPORT");
			report.setAccessible(true);
			CountDownLatch start = new CountDownLatch(1);
			List<Throwable> failures = new CopyOnWriteArrayList<>();
			List<Thread> workers = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				workers.add(Thread.ofVirtual().unstarted(() -> {
					try {
						start.await();
						bump.invoke(null, (Object) Long.valueOf(perThread));
					}
					catch (Throwable ex) {
						failures.add(ex);
					}
				}));
			}
			workers.forEach(Thread::start);
			start.countDown();
			for (Thread worker : workers) {
				worker.join(TimeUnit.MINUTES.toMillis(1));
				assertThat(worker.isAlive()).as("worker finished (a lost release would hang here)").isFalse();
			}
			assertThat(failures).isEmpty();
			assertThat(report.invoke(null)).isEqualTo(Long.valueOf((long) threads * perThread));
		}
	}

}
