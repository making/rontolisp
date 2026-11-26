package am.ik.rontolisp.eval;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

/**
 * The SWANK server an interpreted program's {@code swank:create-server} starts on its own
 * evaluator. The server lives in {@code cli}, which {@code eval} may not import, so the
 * command line hands each evaluator it creates a host
 * ({@link LispEvaluator#setSwankHost}) before the program runs; an evaluator without one
 * signals.
 */
public interface SwankHost {

	/**
	 * Listens on a port, accepting nothing yet.
	 * @param host the interface to bind, {@code null} for the loopback one
	 * @param port the port, 0 for any free one
	 * @return the port listened on
	 * @throws IOException when the port cannot be listened on
	 */
	int listen(@Nullable String host, int port) throws IOException;

	/**
	 * Serves the server listening on a port, as SWANK's {@code create-server} does: with
	 * {@code dontClose}, every connection, accepted on a thread of its own while the
	 * caller goes on; without it, the first connection, waited for on the calling thread,
	 * and then the port is closed.
	 * @param port a port {@link #listen} answered
	 * @param dontClose whether to accept every connection
	 * @throws IOException when accepting fails
	 */
	void serve(int port, boolean dontClose) throws IOException;

	/**
	 * Stops listening on a port, the connections already made going on.
	 * @param port the port
	 * @return whether a server listened on it
	 */
	boolean stop(int port);

}
