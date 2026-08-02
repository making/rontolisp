package am.ik.rontolisp.eval;

import java.util.function.Supplier;

import am.ik.rontolisp.LispFuture;
import am.ik.rontolisp.LispThread;
import am.ik.rontolisp.LispVal;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link AsyncRuntime}. GraalVM Web Image has no thread
 * support (no JSPI either), so an {@code rontolisp:async-defun} body cannot progress in
 * parallel with its caller in the browser playground: the body runs synchronously to
 * completion and an already-settled future is returned. Awaiting still behaves
 * identically; only overlap between async bodies is absent (documented playground
 * limitation). {@code rontolisp:fetch} keeps its own overlap through the worker/SAB
 * broker ({@code Target_HttpSupport}), independent of this substitution.
 */
@TargetClass(AsyncRuntime.class)
final class Target_AsyncRuntime {

	@Substitute
	static LispFuture run(Supplier<LispVal> body) {
		try {
			return LispFuture.settled(body.get());
		}
		catch (RuntimeException ex) {
			return LispFuture.failed(ex);
		}
	}

	@Substitute
	static void releaseHandoffIfPending() {
	}

	@Substitute
	static LispThread spawnThread(Supplier<LispVal> body) {
		// no threads in the browser worker: the body runs synchronously to completion
		// and an already-settled handle is returned (the async-run precedent above).
		java.util.concurrent.CompletableFuture<LispVal> result = new java.util.concurrent.CompletableFuture<>();
		try {
			result.complete(body.get());
		}
		catch (RuntimeException ex) {
			result.completeExceptionally(ex);
		}
		return new LispThread(Thread.currentThread(), result);
	}

	@Substitute
	static LispFuture timer(long millis) {
		// no timer thread in the browser worker: the delay degenerates to zero
		return LispFuture.settled(am.ik.rontolisp.LispNil.INSTANCE);
	}

}
