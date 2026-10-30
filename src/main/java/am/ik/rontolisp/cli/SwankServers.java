package am.ik.rontolisp.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.eval.CommonLispSwank;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.SwankHost;

/**
 * The SWANK servers an interpreted program starts with {@code swank:create-server}: the
 * server {@code rontolisp --swank} runs, on the program's own evaluator, while the
 * program goes on. Its threads keep the process alive as the program's own threads do: a
 * program that ran off its end is served until the server stops and its connections end.
 */
final class SwankServers implements SwankHost {

	private final LispEvaluator evaluator;

	private final CommonLispSwank lisp;

	private final long stackBytes;

	private final @Nullable IntConsumer processExit;

	private final Map<Integer, SwankServer> listening = new ConcurrentHashMap<>();

	/**
	 * The servers of an evaluator.
	 * @param evaluator the evaluator every request runs on
	 * @param stackBytes the stack of each request's thread
	 * @param processExit what ends the process when a request quits, {@code null} when
	 * the command line is embedded (only that server then closes)
	 */
	SwankServers(LispEvaluator evaluator, long stackBytes, @Nullable IntConsumer processExit) {
		this.evaluator = evaluator;
		this.lisp = new CommonLispSwank(evaluator);
		this.stackBytes = stackBytes;
		this.processExit = processExit;
	}

	@Override
	public int listen(@Nullable String host, int port) throws IOException {
		InetAddress bind = host == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(host);
		SwankServer server = new SwankServer(this.lisp, this.evaluator, bind, port, this.stackBytes, this::exit);
		this.listening.put(server.port(), server);
		return server.port();
	}

	@Override
	public void serve(int port, boolean dontClose) throws IOException {
		SwankServer server = this.listening.get(port);
		if (server == null) {
			throw new IOException("no server listens on port " + port);
		}
		if (!dontClose) {
			try {
				server.accept();
			}
			finally {
				stop(port);
			}
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				server.serve();
			}
			catch (IOException | LispExitSignal ex) {
				// the port failed, or a request quit (exit has ended the process then)
			}
			finally {
				this.listening.remove(port, server);
			}
		}, "Swank " + port);
		thread.setDaemon(false);
		thread.start();
	}

	@Override
	public boolean stop(int port) {
		SwankServer server = this.listening.remove(port);
		if (server == null) {
			return false;
		}
		server.stopListening();
		return true;
	}

	private void exit(LispExitSignal signal) {
		this.evaluator.flushOpenStreams();
		IntConsumer exit = this.processExit;
		if (exit != null) {
			exit.accept(signal.code());
		}
	}

}
