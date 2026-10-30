package am.ik.rontolisp.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.Version;
import am.ik.rontolisp.cli.SwankWire.Keyword;
import am.ik.rontolisp.cli.SwankWire.Symbol;
import am.ik.rontolisp.eval.CommonLispSwank;
import am.ik.rontolisp.eval.SwankLocation;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.LispInterruptSignal;
import am.ik.rontolisp.eval.SwankInspector;
import am.ik.rontolisp.eval.SwankSources;

/**
 * The SWANK server {@code rontolisp --swank} runs: SLIME's protocol over TCP
 * ({@link SwankWire}), answering the requests of SLIME 2.32 and its {@code slime-fancy}
 * contribs over the interpreter ({@link CommonLispSwank}), in the shapes SWANK 2.32 on
 * SBCL answers them, and those of SLY 1.0.43 in Slynk's ({@link SwankDialect}): a
 * request's dialect is the package of the function it calls, and SLY's REPL is a channel
 * ({@link MRepl}).
 *
 * <p>
 * The communication style is SWANK's {@code :spawn}: each connection's reader dispatches
 * a request ({@code :emacs-rex}) to the connection's REPL thread when it names
 * {@code :repl-thread} and to a thread of its own otherwise, every thread sized like the
 * command line's. A request's output streams to the editor ({@code :write-string}, each
 * write waiting for the editor's {@code :write-done}), and its standard input reads what
 * the editor's REPL types ({@code :read-string}).
 *
 * <p>
 * In a SLIME request an error no handler handles enters SWANK's debugger on the thread it
 * was signalled on, before anything unwinds ({@code :debug}, {@code :debug-activate}):
 * the thread answers the requests the editor's SLDB sends it until one invokes a restart,
 * the program's or one a request established (SWANK's top level, a retry, a debugger
 * level), and announces the level's end ({@code :debug-return}). An
 * {@code :emacs-interrupt} enters it where it stops the newest request's evaluation, a
 * {@code CONTINUE} restart going on with it. A SLY or Lem request has no debugger: its
 * error, like its interrupt, aborts it.
 */
final class SwankServer implements AutoCloseable {

	/**
	 * How many frames a debugger level announces, SWANK's {@code *sldb-initial-frames*}.
	 */
	private static final int INITIAL_FRAMES = 20;

	private final CommonLispSwank lisp;

	private final LispEvaluator evaluator;

	private final ServerSocket server;

	private final long stackBytes;

	private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

	/** Each dialect's modules {@code connection-info} reports loaded. */
	private final Map<SwankDialect, Set<String>> modules = new ConcurrentHashMap<>();

	private volatile @Nullable LispExitSignal exit;

	/**
	 * For a server a program started ({@link SwankServers}), what a request's quit does
	 * once the server closed; {@code null} for the command line's, whose {@link #serve}
	 * throws it.
	 */
	private final @Nullable Consumer<LispExitSignal> onExit;

	/**
	 * A server listening on a port, which {@link #serve} runs for the command line.
	 * @param lisp the Common Lisp half every request evaluates through
	 * @param evaluator the evaluator it runs on
	 * @param bind the address to listen on
	 * @param port the port, 0 for any free one
	 * @param stackBytes the stack of each request's thread
	 * @throws IOException when the port cannot be listened on
	 */
	SwankServer(CommonLispSwank lisp, LispEvaluator evaluator, InetAddress bind, int port, long stackBytes)
			throws IOException {
		this(lisp, evaluator, bind, port, stackBytes, null);
	}

	/**
	 * A server listening on a port.
	 * @param lisp the Common Lisp half every request evaluates through
	 * @param evaluator the evaluator it runs on
	 * @param bind the address to listen on
	 * @param port the port, 0 for any free one
	 * @param stackBytes the stack of each request's thread
	 * @param onExit for a server a program started, what a request's quit does: its
	 * connections then keep the process alive as the program's own threads do
	 * @throws IOException when the port cannot be listened on
	 */
	SwankServer(CommonLispSwank lisp, LispEvaluator evaluator, InetAddress bind, int port, long stackBytes,
			@Nullable Consumer<LispExitSignal> onExit) throws IOException {
		this.lisp = lisp;
		this.evaluator = evaluator;
		this.stackBytes = stackBytes;
		this.onExit = onExit;
		this.server = new ServerSocket(port, 50, bind);
		lisp.recordLoads();
	}

	/**
	 * The port the server listens on.
	 * @return the port
	 */
	int port() {
		return this.server.getLocalPort();
	}

	/**
	 * The line SWANK's {@code create-server} prints once it listens.
	 * @return the line
	 */
	String startedMessage() {
		return ";; Swank started at port: " + port() + ".";
	}

	/**
	 * Accepts connections until the server closes, or until an evaluation asks the
	 * process to end.
	 * @throws IOException when accepting fails other than by the close
	 * @throws LispExitSignal what an evaluation's {@code exit} asked for
	 */
	void serve() throws IOException {
		while (accept()) {
			// the next connection
		}
		LispExitSignal asked = this.exit;
		if (asked != null) {
			throw asked;
		}
	}

	/**
	 * Accepts one connection and starts reading it: on a virtual thread for the command
	 * line's server, on a platform one that keeps the process alive for a program's.
	 * @return false when the server closed instead
	 * @throws IOException when accepting fails other than by the close
	 */
	boolean accept() throws IOException {
		Socket socket;
		try {
			socket = this.server.accept();
		}
		catch (SocketException ex) {
			if (this.server.isClosed()) {
				return false;
			}
			throw ex;
		}
		Connection connection = new Connection(socket);
		this.connections.add(connection);
		(this.onExit == null ? Thread.ofVirtual() : Thread.ofPlatform().daemon(false)).name("swank-reader")
			.start(connection);
		return true;
	}

	/** Closes the port, the connections made going on (SWANK's {@code stop-server}). */
	void stopListening() {
		try {
			this.server.close();
		}
		catch (IOException ex) {
			// closing anyway
		}
	}

	@Override
	public void close() {
		stopListening();
		for (Connection connection : this.connections) {
			connection.close();
		}
	}

	/** One {@code :emacs-rex} request. */
	private record Request(Object form, @Nullable String pkg, Object id) {
	}

	/** A thread running a request, which an interrupt can stop. */
	private record Running(Thread thread) {
	}

	/**
	 * What an evaluation came to.
	 *
	 * @param value what it answered
	 * @param aborted the condition it aborted on, as SWANK prints it, or null
	 */
	private record Outcome(@Nullable Object value, @Nullable String aborted) {
	}

	/** A request a thread with a debugger runs, innermost first on its session. */
	private static final class Pending {

		final Object id;

		/**
		 * The last condition a debugger level was entered for while the request ran, what
		 * its {@code :abort} reports, as SWANK's {@code eval-for-emacs} records every
		 * condition signalled in its extent.
		 */
		volatile CommonLispSwank.@Nullable Debugging condition;

		Pending(Object id) {
			this.id = id;
		}

	}

	/** What ends a request a top-level restart left. */
	private enum Unwound {

		/** SWANK's top level: the request aborts, its thread goes on. */
		TOP_LEVEL,
		/** The thread's own abort: the request aborts and its thread ends. */
		THREAD

	}

	/** What a retry restart's invocation answers, to run the body again. */
	private enum Retry {

		AGAIN

	}

	private Set<String> modules(SwankDialect dialect) {
		return this.modules.computeIfAbsent(dialect,
				key -> Collections.synchronizedSet(new LinkedHashSet<>(key.modules())));
	}

	/**
	 * One editor's socket: its messages read on one thread, its replies written whole,
	 * its REPL thread and the requests running.
	 */
	private final class Connection implements Runnable {

		private final Socket socket;

		private final OutputStream out;

		private final LinkedBlockingQueue<Request> replQueue = new LinkedBlockingQueue<>();

		private volatile @Nullable Thread replThread;

		private final ConcurrentLinkedDeque<Running> running = new ConcurrentLinkedDeque<>();

		/** Each worker's session by its thread's id, while it runs. */
		private final Map<Long, Session> workers = new ConcurrentHashMap<>();

		/**
		 * Each waiting writer's and reader's mailbox, by the id it put in its message.
		 */
		private final Map<Long, BlockingQueue<List<?>>> writeDone = new ConcurrentHashMap<>();

		private final Map<Long, BlockingQueue<List<?>>> input = new ConcurrentHashMap<>();

		private final Map<Long, BlockingQueue<List<?>>> minibuffer = new ConcurrentHashMap<>();

		/** The editor's inspector, which keeps what it inspected between requests. */
		private final SwankInspector inspector = SwankServer.this.lisp.newInspector();

		private final AtomicLong tags = new AtomicLong();

		/** SLY's REPLs, by the channel id the server gave each. */
		private final Map<Long, MRepl> channels = new ConcurrentSkipListMap<>();

		private final AtomicLong channelIds = new AtomicLong();

		/** The ids of the values a micros REPL printed and pretty-eval answered. */
		private final AtomicLong objectIds = new AtomicLong();

		private volatile boolean closed;

		/** The dialect the editor's last request spoke. */
		private volatile SwankDialect dialect = SwankDialect.SWANK;

		Connection(Socket socket) throws IOException {
			this.socket = socket;
			this.out = new BufferedOutputStream(socket.getOutputStream());
		}

		@Override
		public void run() {
			try (InputStream in = new BufferedInputStream(this.socket.getInputStream())) {
				for (Object event = SwankWire.read(in); event != null; event = SwankWire.read(in)) {
					if (event instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Keyword kind) {
						dispatch(kind.name().toLowerCase(Locale.ROOT), list);
					}
				}
			}
			catch (IOException ex) {
				// the editor went away, or sent no SWANK message: the connection ends
			}
			finally {
				close();
				SwankServer.this.connections.remove(this);
			}
		}

		private void dispatch(String kind, List<?> event) {
			switch (kind) {
				case "emacs-rex" -> {
					if (event.size() < 5) {
						return;
					}
					String pkg = event.get(2) instanceof String name ? name : null;
					Request request = new Request(event.get(1), pkg, event.get(4));
					Object thread = event.get(3);
					Thread repl = this.replThread;
					MRepl mrepl = thread instanceof Number id ? mreplOn(id.longValue()) : null;
					if (thread instanceof Keyword keyword && keyword.name().equalsIgnoreCase("repl-thread")
							|| thread instanceof Number id && repl != null && id.longValue() == repl.threadId()) {
						// queued first: a REPL thread ending now sees it and starts the
						// next
						this.replQueue.add(request);
						startRepl();
					}
					else if (mrepl != null) {
						mrepl.post(request);
					}
					else if (thread instanceof Number id
							&& this.workers.get(id.longValue()) instanceof Session worker) {
						// a worker's debugger level answers what its SLDB sends it
						worker.mailbox.add(request);
					}
					else if (thread instanceof Number id) {
						send(list(kw("invalid-rpc"), request.id(), "Thread not found: " + id));
					}
					else {
						Thread worker = new Thread(null, () -> {
							long self = Thread.currentThread().threadId();
							Session session = new Session("worker", new LinkedBlockingQueue<>());
							this.workers.put(self, session);
							try {
								run(request, null, session);
							}
							finally {
								this.workers.remove(self);
							}
						}, "swank-worker", SwankServer.this.stackBytes);
						worker.setDaemon(true);
						worker.start();
					}
				}
				case "emacs-channel-send" -> {
					MRepl channel = event.size() > 2 && event.get(1) instanceof Number id
							? this.channels.get(id.longValue()) : null;
					if (channel != null) {
						channel.post(event.get(2));
					}
					else if (event.size() > 1) {
						send(list(kw("invalid-channel"), event.get(1), "No such channel"));
					}
				}
				case "emacs-interrupt" -> interrupt(event.size() > 1 ? event.get(1) : Boolean.TRUE);
				case "write-done" -> deliver(this.writeDone, event);
				case "emacs-return-string" -> deliver(this.input, event);
				case "emacs-return" -> deliver(this.minibuffer, event);
				default -> {
					// :emacs-pong and the debugger's events: nothing waits for them
				}
			}
		}

		private void deliver(Map<Long, BlockingQueue<List<?>>> boxes, List<?> event) {
			if (event.size() > 1 && event.get(1) instanceof Number id) {
				BlockingQueue<List<?>> box = boxes.get(id.longValue());
				if (box != null) {
					box.offer(event);
				}
			}
		}

		synchronized void startRepl() {
			if (this.replThread != null) {
				return;
			}
			Thread thread = new Thread(null, () -> {
				Session session = new Session("repl-thread", this.replQueue);
				while (!this.closed) {
					try {
						Request request = this.replQueue.poll(1, TimeUnit.SECONDS);
						if (request != null && run(request, null, session) == Unwound.THREAD) {
							break;
						}
					}
					catch (InterruptedException ex) {
						// an interrupt that found no request running: wait on
					}
				}
				replEnded();
			}, "swank-repl-thread", SwankServer.this.stackBytes);
			thread.setDaemon(true);
			this.replThread = thread;
			thread.start();
		}

		/**
		 * The REPL thread ended, by its own abort restart: the next REPL request starts
		 * another, as SWANK spawns a REPL thread for one that died.
		 */
		private synchronized void replEnded() {
			if (this.replThread == Thread.currentThread()) {
				this.replThread = null;
				if (!this.closed && !this.replQueue.isEmpty()) {
					startRepl();
				}
			}
		}

		private void interrupt(Object target) {
			// t names the newest request running, a number the thread of that id
			Thread repl = this.replThread;
			Object thread = target instanceof Keyword keyword && keyword.name().equalsIgnoreCase("repl-thread")
					&& repl != null ? (Object) repl.threadId() : target;
			for (Running each : this.running) {
				if (!(thread instanceof Number id) || each.thread().threadId() == id.longValue()) {
					SwankServer.this.evaluator.interrupt(each.thread());
					return;
				}
			}
		}

		private @Nullable MRepl mreplOn(long threadId) {
			for (MRepl each : this.channels.values()) {
				if (each.thread.threadId() == threadId) {
					return each;
				}
			}
			return null;
		}

		/**
		 * Runs a request on the calling thread and answers it. A SLIME request on a
		 * thread with a session has the debugger: one the thread starts with runs under
		 * SWANK's top-level restarts, one a debugger level runs inside the evaluation it
		 * stopped, and a restart invoked further out passes on, the request answering
		 * {@code :abort} first.
		 * @param request the request
		 * @param channel the SLY REPL whose thread runs it, or null
		 * @param session the request thread's session, or null for a SLY REPL's thread
		 * @return how a top-level restart ended it, or null when none did
		 */
		@Nullable Unwound run(Request request, @Nullable MRepl channel, @Nullable Session session) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			long self = Thread.currentThread().threadId();
			SwankDialect dialect = dialectOf(request.form());
			MRepl target = channel != null ? channel : dialect == SwankDialect.SLYNK ? defaultChannel() : null;
			Output output = new Output(this, self, dialect, target);
			Input input = new Input(channel != null ? channel::readLine : dialect == SwankDialect.SLYNK
					? () -> line(readFromMinibuffer("Input for thread " + self + "? ")) : () -> readString(self));
			Session debugged = dialect == SwankDialect.SWANK ? session : null;
			Pending pending = new Pending(request.id());
			boolean topLevel = debugged != null && debugged.requests.isEmpty();
			if (debugged != null) {
				debugged.requests.push(pending);
				debugged.outputs.push(output);
			}
			Supplier<@Nullable Object> body;
			if (topLevel) {
				body = () -> topLevel(Objects.requireNonNull(debugged), request, output);
			}
			else {
				body = () -> call(request.form(), output, channel, debugged);
			}
			Outcome outcome;
			try {
				outcome = execute(lisp.guessPackage(request.pkg()), output, input, debugged,
						() -> lisp.withFrame(lisp(request.form()), body));
			}
			catch (RuntimeException ex) {
				// a restart further out (execute lets only a transfer through): this
				// request ends on its way there
				send(aborted(pending));
				throw ex;
			}
			finally {
				if (debugged != null) {
					debugged.requests.pop();
					debugged.outputs.pop();
				}
			}
			if (outcome == null) {
				return null;
			}
			if (outcome.value() instanceof Unwound how) {
				send(aborted(pending));
				return how;
			}
			send(list(kw("return"),
					outcome.aborted() == null ? list(kw("ok"), outcome.value()) : list(kw("abort"), outcome.aborted()),
					request.id()));
			return null;
		}

		/**
		 * A request's {@code :abort}: the last condition it entered a debugger for, or
		 * nil.
		 */
		private List<Object> aborted(Pending pending) {
			CommonLispSwank.Debugging condition = pending.condition;
			return list(kw("return"), list(kw("abort"), condition == null ? "NIL" : condition.describe()), pending.id);
		}

		/**
		 * A request the thread starts with, under SWANK's top-level restarts: the
		 * thread's own abort, and inside it the quit restart {@code throw-to-toplevel}
		 * invokes.
		 */
		private @Nullable Object topLevel(Session session, Request request, Output output) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			return lisp.withRestart(LispNames.ABORT, "abort thread (" + session.describe() + ")", false,
					() -> lisp.withRestart(LispNames.ABORT, "Return to SLIME's top level.", true,
							() -> call(request.form(), output, null, session), () -> Unwound.TOP_LEVEL),
					() -> Unwound.THREAD);
		}

		/**
		 * Evaluates on the calling thread, which an interrupt can then stop, with the
		 * package bound and the standard streams over the editor's.
		 * @param pkg the package's canonical name, null for {@code cl-user}
		 * @param output where the standard output writes
		 * @param input where the standard input reads
		 * @param debugger the thread's debugger, or null for none
		 * @param body the evaluation
		 * @return what it came to, null when it ended the process
		 */
		@Nullable Outcome execute(@Nullable String pkg, Output output, Input input, @Nullable Session debugger,
				Supplier<@Nullable Object> body) {
			Running running = new Running(Thread.currentThread());
			this.running.addFirst(running);
			Runnable restore = SwankServer.this.lisp.controlState();
			try {
				Object value = SwankServer.this.lisp.serve(pkg, output, input, debugger, () -> body.get());
				output.flush();
				return new Outcome(value, null);
			}
			catch (LispInterruptSignal ex) {
				// what it printed before it stopped still reaches the editor
				output.flush();
				return new Outcome(null, "#<SIMPLE-ERROR \"Interrupt from Emacs\">");
			}
			catch (LispExitSignal ex) {
				// the oracle's quit ends its process, and the editor's connection with it
				output.flush();
				SwankServer.this.exit = ex;
				SwankServer.this.close();
				Consumer<LispExitSignal> onExit = SwankServer.this.onExit;
				if (onExit != null) {
					onExit.accept(ex);
				}
				return null;
			}
			catch (StackOverflowError ex) {
				restore.run();
				output.flush();
				return new Outcome(null, SwankServer.this.lisp.describeFailure(ex));
			}
			catch (RuntimeException ex) {
				output.flush();
				if (SwankServer.this.lisp.isTransfer(ex)) {
					throw ex;
				}
				return new Outcome(null, SwankServer.this.lisp.describeFailure(ex));
			}
			finally {
				this.running.remove(running);
				SwankServer.this.evaluator.clearInterrupt();
			}
		}

		/** The first SLY REPL, where Slynk's other requests write their output. */
		private @Nullable MRepl defaultChannel() {
			Iterator<MRepl> first = this.channels.values().iterator();
			return first.hasNext() ? first.next() : null;
		}

		/**
		 * Asks SLIME's REPL for a line of standard input ({@code :read-string}) and waits
		 * for it ({@code :emacs-return-string}).
		 * @param id the reading thread's id
		 * @return the line, or null at the end of the input
		 */
		@Nullable String readString(long id) {
			long tag = nextTag();
			BlockingQueue<List<?>> box = new LinkedBlockingQueue<>();
			this.input.put(id, box);
			send(list(kw("read-string"), id, tag));
			boolean read = false;
			try {
				while (true) {
					List<?> event;
					try {
						event = box.poll(1, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						// the interrupt enters the debugger, and its continue reads on
						SwankServer.this.lisp.checkInterrupt();
						continue;
					}
					if (event != null) {
						read = true;
						return event.size() > 3 && event.get(3) instanceof String text && !text.isEmpty() ? text : null;
					}
					if (this.closed) {
						read = true;
						return null;
					}
				}
			}
			finally {
				this.input.remove(id, box);
				if (!read) {
					// left by an interrupt or a restart: the editor stops reading
					send(list(kw("read-aborted"), id, tag));
				}
			}
		}

		/**
		 * A minibuffer's answer as a line of input, Slynk's {@code read-input} ending it.
		 */
		private static @Nullable String line(@Nullable String answer) {
			return answer == null || answer.isEmpty() ? null : answer + "\n";
		}

		/**
		 * Answers a request's form: a function its dialect defines, else the form
		 * evaluated.
		 * @param form the form
		 * @param output the request's output
		 * @param channel the SLY REPL whose thread runs the request, or null
		 * @param session the request thread's session where it has the debugger, or null
		 */
		private @Nullable Object call(Object form, Output output, @Nullable MRepl channel, @Nullable Session session) {
			if (!(form instanceof List<?> call) || call.isEmpty() || !(call.getFirst() instanceof Symbol operator)) {
				throw new IllegalArgumentException("not a function call: " + SwankWire.print(form));
			}
			List<Object> args = new ArrayList<>();
			for (Object arg : call.subList(1, call.size())) {
				args.add(literal(arg));
			}
			SwankDialect dialect = SwankDialect.of(operator.pkg());
			if (dialect == null) {
				return evaluate(call);
			}
			this.dialect = dialect;
			CommonLispSwank lisp = SwankServer.this.lisp;
			String member = dialect.canonical(operator.member());
			if (dialect == SwankDialect.SLYNK) {
				switch (member) {
					case "swank-require" -> {
						List<String> loaded = new ArrayList<>();
						for (Object module : args.isEmpty() ? List.of() : asList(args.getFirst())) {
							modules(dialect).add(moduleName(module));
							loaded.add(String.valueOf(module));
						}
						return List.of(List.copyOf(modules(dialect)), loaded);
					}
					case "swank-add-load-paths" -> {
						return null;
					}
					case "create-mrepl" -> {
						return createMrepl(integer(args, 0, 1));
					}
					case "eval-for-mrepl" -> {
						return evalForMrepl(args, channel);
					}
					case "simple-completions" -> {
						List<String> strings = new ArrayList<>();
						for (CommonLispSwank.Completion each : lisp.simpleCompletions(text(args, 0),
								optionalText(args, 1))) {
							strings.add(each.candidate());
						}
						return List.of(strings, commonPrefix(strings));
					}
					case "flex-completions" -> {
						String pattern = text(args, 0);
						if (pattern.isEmpty()) {
							return null;
						}
						List<Object> candidates = new ArrayList<>();
						for (CommonLispSwank.FlexCompletion each : lisp.flexCompletions(pattern, optionalText(args, 1),
								key(args, 2, "limit") instanceof Number limit ? limit.intValue() : 300)) {
							List<Object> chunks = new ArrayList<>();
							for (Map.Entry<Integer, String> chunk : each.chunks()) {
								chunks.add(List.of((long) chunk.getKey(), chunk.getValue()));
							}
							candidates.add(List.of(each.text(), (double) each.score(), chunks, each.classification()));
						}
						return Arrays.asList(candidates, null);
					}
					default -> {
						// SWANK's answer
					}
				}
			}
			if (dialect == SwankDialect.MICROS) {
				switch (operator.pkg() + ":" + member) {
					case "micros:connection-info" -> lisp.defineMicrosLoadFile();
					case "micros/pretty-eval:pretty-eval" -> {
						return list(kw("value"), lisp.interactiveEval(text(args, 0), false, dialect.echo(), 0, 0),
								kw("id"), this.objectIds.incrementAndGet());
					}
					case "micros/lsp-api:completions" -> {
						List<Object> items = new ArrayList<>();
						for (CommonLispSwank.LspCompletion each : lisp.lspCompletions(text(args, 0),
								optionalText(args, 1))) {
							List<Object> chunks = new ArrayList<>();
							for (Map.Entry<Integer, String> chunk : each.chunks()) {
								chunks.add(List.of((long) chunk.getKey(), chunk.getValue()));
							}
							items.add(list(each.label(), chunks, each.kind(), each.signature(), each.documentation(),
									String.format("%010d", items.size())));
						}
						return items;
					}
					case "micros/lsp-api:hover-symbol" -> {
						return lisp.hoverSymbol(text(args, 0));
					}
					default -> {
						// SWANK's answer
					}
				}
			}
			return switch (member) {
				case "connection-info" -> connectionInfo(dialect);
				case "swank-require" -> {
					for (Object module : args.isEmpty() ? List.of() : asList(args.getFirst())) {
						modules(dialect).add(moduleName(module));
					}
					yield List.copyOf(modules(dialect));
				}
				case "create-repl" -> {
					startRepl();
					yield List.of(lisp.packageName(lisp.currentPackage()), lisp.prompt(lisp.currentPackage()));
				}
				case "listener-eval" -> retrying(session, "Retry SLIME REPL evaluation request.",
						() -> listenerEval(text(args, 0), output, dialect));
				case "interactive-eval",
						"interactive-eval-region" ->
					retrying(session, "Retry SLIME interactive evaluation request.",
							() -> lisp.interactiveEval(text(args, 0), member.endsWith("region"), dialect.echo(),
									integer(args, 1, 1), integer(args, 2, 80)));
				case "eval-and-grab-output" -> retrying(session, "Retry SLIME evaluation request.",
						() -> lisp.evalAndGrabOutput(text(args, 0), dialect.echo()));
				case "pprint-eval" -> lisp.pprintEval(text(args, 0));
				case "re-evaluate-defvar" ->
					retrying(session, "Retry SLIME evaluation request.", () -> lisp.reEvaluateDefvar(text(args, 0)));
				// SLDB: what the debugger level running on this thread answers
				case "throw-to-toplevel" -> lisp.throwToToplevel();
				case "invoke-nth-restart-for-emacs" -> level(session) instanceof CommonLispSwank.Debugging level
						&& integer(args, 0, -1) == Objects.requireNonNull(session).levels.size()
								? wireOrNil(level.invokeNth(integer(args, 1, -1))) : null;
				case "invoke-nth-restart" -> level(session) instanceof CommonLispSwank.Debugging level
						? wireOrNil(level.invokeNth(integer(args, 0, -1))) : null;
				case "sldb-abort" -> level(session) instanceof CommonLispSwank.Debugging level
						? wireOrNil(level.invokeNamed(LispNames.ABORT)) : null;
				case "sldb-continue" -> level(session) instanceof CommonLispSwank.Debugging level
						? wireOrNil(level.invokeNamed(LispNames.CONTINUE)) : null;
				case "backtrace" -> level(session) instanceof CommonLispSwank.Debugging level
						? backtrace(level, integer(args, 0, 0), integer(args, 1, Integer.MAX_VALUE)) : List.of();
				case "debugger-info-for-emacs" -> level(session) instanceof CommonLispSwank.Debugging level
						? debuggerInfo(Objects.requireNonNull(session), level, integer(args, 0, 0),
								integer(args, 1, Integer.MAX_VALUE))
						: null;
				case "frame-locals-and-catch-tags" -> {
					List<Object> locals = new ArrayList<>();
					int index = integer(args, 0, -1);
					if (level(session) instanceof CommonLispSwank.Debugging level && index >= 0
							&& index < level.frameCount()) {
						for (CommonLispSwank.Local local : level.locals(index)) {
							locals.add(list(kw("name"), local.name(), kw("id"), (long) local.id(), kw("value"),
									local.value()));
						}
					}
					yield Arrays.asList(locals, null);
				}
				case "frame-package-name" -> level(session) instanceof CommonLispSwank.Debugging level
						? level.framePackage(integer(args, 0, 0)) : lisp.packageName(lisp.currentPackage());
				case "eval-string-in-frame" -> level(session) instanceof CommonLispSwank.Debugging level
						? level.evalInFrame(text(args, 0), integer(args, 1, 0), optionalText(args, 2),
								integer(args, 3, 1), integer(args, 4, 80))
						: lisp.interactiveEval(text(args, 0), false, dialect.echo(), integer(args, 3, 1),
								integer(args, 4, 80));
				case "pprint-eval-string-in-frame" -> level(session) instanceof CommonLispSwank.Debugging level
						? level.pprintEvalInFrame(text(args, 0), integer(args, 1, 0), optionalText(args, 2))
						: lisp.pprintEval(text(args, 0));
				case "frame-source-location" -> list(kw("error"), "No source location");
				case "set-package" -> {
					String found = lisp.guessPackage(text(args, 0));
					if (found == null) {
						throw new IllegalArgumentException("Package " + text(args, 0) + " doesn't exist.");
					}
					yield List.of(lisp.packageName(found), lisp.prompt(found));
				}
				case "list-all-package-names" -> lisp.packageNames(args.size() > 0 && truthy(args.getFirst()));
				case "compile-string-for-emacs" -> compilation(lisp.compileString(text(args, 0), text(args, 1),
						position(args.size() > 2 ? args.get(2) : null), optionalText(args, 3)), false);
				case "compile-file-for-emacs", "compile-file-if-needed" ->
					compilation(lisp.compileFile(text(args, 0), args.size() > 1 && truthy(args.get(1))), false);
				case "load-file" -> lisp.loadFile(text(args, 0));
				case "simple-completions" -> completions(lisp.simpleCompletions(text(args, 0), optionalText(args, 1)));
				case "completions" -> completions(lisp.compoundCompletions(text(args, 0), optionalText(args, 1)));
				case "fuzzy-completions" -> {
					List<Object> candidates = new ArrayList<>();
					for (CommonLispSwank.FuzzyCompletion each : lisp.fuzzyCompletions(text(args, 0),
							optionalText(args, 1),
							key(args, 2, "limit") instanceof Number limit ? limit.intValue() : 0)) {
						List<Object> chunks = new ArrayList<>();
						for (Map.Entry<Integer, String> chunk : each.chunks()) {
							chunks.add(List.of((long) chunk.getKey(), chunk.getValue()));
						}
						candidates.add(List.of(each.candidate(), each.flags(), each.qualified(), chunks));
					}
					yield Arrays.asList(candidates, null);
				}
				case "fuzzy-completion-selected", "buffer-first-change", "update-indentation-information",
						"init-presentations" ->
					null;
				case "clear-repl-results" -> Boolean.TRUE;
				case "autodoc" -> {
					List<?> raw = args.isEmpty() ? List.of() : asList(args.getFirst());
					CommonLispSwank.Autodoc doc = lisp.autodoc(cursor(raw));
					yield doc == null ? List.of(kw("not-available"), Boolean.TRUE)
							: Arrays.asList(doc.text(), doc.cacheable());
				}
				case "operator-arglist" -> lisp.operatorArglist(text(args, 0));
				case "describe-symbol" -> lisp.describeSymbol(text(args, 0));
				case "describe-function" -> lisp.describeFunction(text(args, 0));
				case "documentation-symbol" -> lisp.documentationSymbol(text(args, 0));
				case "swank-macroexpand-1", "swank-expand-1" ->
					lisp.macroexpand(text(args, 0), CommonLispSwank.Expansion.ONCE);
				case "swank-macroexpand", "swank-expand" ->
					lisp.macroexpand(text(args, 0), CommonLispSwank.Expansion.REPEATEDLY);
				case "swank-macroexpand-all" -> lisp.macroexpand(text(args, 0), CommonLispSwank.Expansion.ALL);
				case "find-definitions-for-emacs" -> entries(lisp.findDefinitions(text(args, 0)));
				case "xref" -> xref(args.isEmpty() ? null : args.getFirst(), text(args, 1));
				case "xrefs" -> {
					List<Object> found = new ArrayList<>();
					for (Object type : args.isEmpty() ? List.of() : asList(args.getFirst())) {
						if (xref(type, text(args, 1)) instanceof List<?> entries && !entries.isEmpty()) {
							List<Object> group = new ArrayList<>();
							group.add(type);
							group.addAll(entries);
							found.add(group);
						}
					}
					yield found;
				}
				case "init-inspector" -> page(this.inspector.init(text(args, 0), optionalText(args, 1)));
				case "inspect-nth-part" -> page(this.inspector.inspectNthPart(integer(args, 0, 0)));
				case "inspector-nth-part" -> wire(this.inspector.nthPart(integer(args, 0, 0)), lisp);
				case "inspector-range" -> range(this.inspector.range(integer(args, 0, 0), integer(args, 1, 0)));
				case "inspector-call-nth-action" ->
					page(this.inspector.callNthAction(integer(args, 0, 0), this::readFromMinibuffer));
				case "inspector-pop" -> page(this.inspector.pop());
				case "inspector-next" -> page(this.inspector.next());
				case "inspector-reinspect" -> page(this.inspector.reinspect());
				case "inspector-toggle-verbose" -> page(this.inspector.toggleVerbose());
				case "inspector-eval" -> this.inspector.eval(text(args, 0));
				case "inspector-history" -> this.inspector.historyText();
				case "quit-inspector" -> {
					this.inspector.quit();
					yield null;
				}
				case "describe-inspectee" -> this.inspector.describe();
				case "pprint-inspector-part" -> this.inspector.printPart(integer(args, 0, 0));
				case "macro-form-p" -> {
					CommonLispSwank.MacroForm answer = lisp.macroForm(text(args, 0),
							args.size() > 1 && truthy(args.get(1)));
					yield answer.error() != null ? list(kw("error"), answer.error())
							: list(kw("ok"), answer.kind() == null ? null : kw(answer.kind()));
				}
				case "macrostep-expand-1" -> {
					CommonLispSwank.MacroStep step = lisp.macrostepExpand(text(args, 0),
							args.size() > 1 && truthy(args.get(1)));
					if (step.error() != null) {
						yield list(kw("error"), step.error());
					}
					List<Object> subforms = new ArrayList<>();
					for (CommonLispSwank.Subform each : step.subforms()) {
						subforms.add(list(each.operator(), kw(each.kind()), (long) each.start()));
					}
					yield list(kw("ok"), step.expansion(), subforms);
				}
				case "default-directory" -> directory(Path.of("").toAbsolutePath().toString());
				case "clear-repl-variables" -> {
					lisp.clearReplVariables();
					yield null;
				}
				case "ping" -> args.isEmpty() ? null : args.getFirst();
				case "quit-lisp" -> throw new LispExitSignal(0);
				default -> throw new IllegalArgumentException(
						"The function " + operator.name().toUpperCase(Locale.ROOT) + " is undefined.");
			};
		}

		/**
		 * The uses of a name an {@code xref} kind asks for, or {@code :not-implemented}.
		 */
		private Object xref(@Nullable Object type, String name) {
			String kind = type instanceof Keyword keyword ? keyword.name() : String.valueOf(type);
			List<SwankSources.Entry> found = SwankServer.this.lisp.xref(kind, name);
			return found == null ? kw("not-implemented") : entries(found);
		}

		/**
		 * Asks the editor for a line ({@code :read-from-minibuffer}) and waits for it
		 * ({@code :emacs-return}).
		 */
		private @Nullable String readFromMinibuffer(String prompt) {
			long thread = Thread.currentThread().threadId();
			BlockingQueue<List<?>> box = new LinkedBlockingQueue<>();
			this.minibuffer.put(thread, box);
			send(list(kw("read-from-minibuffer"), thread, nextTag(), prompt, null));
			try {
				while (true) {
					List<?> event = box.poll(1, TimeUnit.SECONDS);
					if (event != null) {
						return event.size() > 3 && event.get(3) instanceof String line ? line : null;
					}
					if (this.closed) {
						return null;
					}
				}
			}
			catch (InterruptedException ex) {
				throw new LispInterruptSignal();
			}
			finally {
				this.minibuffer.remove(thread, box);
			}
		}

		private @Nullable Object listenerEval(String code, Output output, SwankDialect dialect) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			String before = lisp.currentPackage();
			List<String> values = lisp.listenerEval(code);
			if (dialect == SwankDialect.SWANK) {
				// the presentation contrib's REPL results start on a line of their own
				output.freshLine();
			}
			output.flush();
			if (values.isEmpty()) {
				send(list(kw("write-string"), "; No value", kw("repl-result")));
			}
			for (String value : values) {
				if (dialect == SwankDialect.MICROS) {
					// a printed object Lem's inspector may ask for by its id
					send(list(kw("write-object"), value, this.objectIds.incrementAndGet(), kw("repl-result")));
					send(list(kw("write-string"), "\n", kw("repl-result")));
				}
				else {
					send(list(kw("write-string"), value + "\n", kw("repl-result")));
				}
			}
			String after = lisp.currentPackage();
			if (!after.equals(before)) {
				send(list(kw("new-package"), lisp.packageName(after), lisp.prompt(after)));
			}
			return null;
		}

		/**
		 * Slynk's {@code create-mrepl}: a REPL on a channel of its own, with a thread of
		 * its own, announced on the editor's channel.
		 * @param remote the editor's channel id
		 * @return the channel's id and its thread's
		 */
		private List<Object> createMrepl(long remote) {
			long id = this.channelIds.incrementAndGet();
			MRepl repl = new MRepl(this, id, remote, SwankServer.this.lisp.currentPackage());
			this.channels.put(id, repl);
			repl.send(list(kw("write-string"),
					"; SLY " + SwankDialect.SLYNK.protocolVersion() + " (#<MREPL mrepl-" + id + "-" + remote + ">)\n"));
			repl.prompt();
			repl.thread.start();
			return list(id, repl.thread.threadId());
		}

		/**
		 * Slynk's {@code eval-for-mrepl}: a function changing a REPL's package run on its
		 * thread, answering the REPL's prompt and the function's values.
		 */
		private List<Object> evalForMrepl(List<Object> args, @Nullable MRepl channel) {
			if (channel == null || args.size() < 2 || !(args.get(1) instanceof Symbol function)) {
				throw new IllegalArgumentException("This SLYFUN can only be called from threads belonging to MREPL");
			}
			CommonLispSwank lisp = SwankServer.this.lisp;
			List<Object> rest = args.subList(2, args.size());
			List<Object> values = switch (function.member()) {
				case "guess-and-set-package" -> {
					channel.pkg = existingPackage(text(rest, 0));
					yield list(Boolean.TRUE);
				}
				case "sync-package-and-default-directory" -> {
					if (key(rest, 0, "package-name") instanceof String name) {
						channel.pkg = existingPackage(name);
					}
					yield list(lisp.packageName(channel.pkg), directory(Path.of("").toAbsolutePath().toString()));
				}
				default -> throw new IllegalArgumentException(
						"The function " + function.name().toUpperCase(Locale.ROOT) + " is undefined.");
			};
			return list(channel.promptArguments(), values);
		}

		private String existingPackage(String designator) {
			String found = SwankServer.this.lisp.guessPackage(designator);
			if (found == null) {
				throw new IllegalArgumentException("Can't find a package for designator " + designator);
			}
			return found;
		}

		private List<Object> connectionInfo(SwankDialect dialect) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			String host;
			try {
				host = InetAddress.getLocalHost().getHostName();
			}
			catch (IOException ex) {
				host = "localhost";
			}
			List<Object> features = new ArrayList<>();
			for (String feature : lisp.features()) {
				features.add(kw(feature));
			}
			String cl = lisp.guessPackage("CL-USER");
			String pkg = cl == null ? "CL-USER" : cl;
			return list(kw("pid"), ProcessHandle.current().pid(), kw("style"), kw("spawn"), kw("encoding"),
					list(kw("coding-systems"), List.of("utf-8-unix")), kw("lisp-implementation"),
					list(kw("type"), "rontolisp", kw("name"), "rontolisp", kw("version"), Version.getVersion(),
							kw("program"), null),
					kw("machine"),
					list(kw("instance"), host, kw("type"), "JVM", kw("version"),
							System.getProperty("java.version", "")),
					kw("features"), features, kw("modules"), List.copyOf(modules(dialect)), kw("package"),
					list(kw("name"), lisp.packageName(pkg), kw("prompt"), lisp.prompt(pkg)), kw("version"),
					dialect.protocolVersion());
		}

		/**
		 * A form the server does not answer itself, evaluated in the request's package.
		 */
		private @Nullable Object evaluate(List<?> call) {
			return wire(SwankServer.this.lisp.evaluate(lisp(call)), SwankServer.this.lisp);
		}

		void send(Object message) {
			synchronized (this.out) {
				try {
					SwankWire.write(this.out, message);
				}
				catch (IOException ex) {
					close();
				}
			}
		}

		void close() {
			this.closed = true;
			try {
				this.socket.close();
			}
			catch (IOException ex) {
				// closing anyway
			}
			for (BlockingQueue<List<?>> box : this.writeDone.values()) {
				box.offer(List.of());
			}
			for (BlockingQueue<List<?>> box : this.input.values()) {
				box.offer(List.of());
			}
			for (Running each : this.running) {
				SwankServer.this.evaluator.interrupt(each.thread());
			}
		}

		long nextTag() {
			return this.tags.incrementAndGet();
		}

		/**
		 * Runs a request's body under a retry restart where the request has the debugger,
		 * as SWANK's {@code with-retry-restart}: invoking it runs the body again.
		 */
		private @Nullable Object retrying(@Nullable Session session, String report, Supplier<@Nullable Object> body) {
			if (session == null) {
				return body.get();
			}
			while (true) {
				Object value = SwankServer.this.lisp.withRestart("SWANK::RETRY", report, false, body,
						() -> Retry.AGAIN);
				if (value != Retry.AGAIN) {
					return value;
				}
			}
		}

		/** The innermost debugger level running on a session, or null. */
		private CommonLispSwank.@Nullable Debugging level(@Nullable Session session) {
			return session == null ? null : session.levels.peek();
		}

		private @Nullable Object wireOrNil(@Nullable LispVal value) {
			return value == null ? null : wire(value, SwankServer.this.lisp);
		}

		/** A debugger level's frames from start to end, as SWANK's {@code backtrace}. */
		private List<Object> backtrace(CommonLispSwank.Debugging level, int start, int end) {
			List<Object> frames = new ArrayList<>();
			for (int i = Math.max(0, start); i < Math.min(end, level.frameCount()); i++) {
				frames.add(list((long) i, level.frame(i)));
			}
			return frames;
		}

		/**
		 * A debugger level's condition, restarts, frames and pending requests, as SWANK's
		 * {@code debugger-info-for-emacs}.
		 */
		private List<Object> debuggerInfo(Session session, CommonLispSwank.Debugging level, int start, int end) {
			List<Object> restarts = new ArrayList<>();
			for (List<String> restart : level.restarts()) {
				restarts.add(List.copyOf(restart));
			}
			List<Object> continuations = new ArrayList<>();
			for (Pending pending : session.requests) {
				continuations.add(pending.id);
			}
			return list(list(level.message(), "   [Condition of type " + level.conditionType() + "]", null), restarts,
					backtrace(level, start, end), continuations);
		}

		/**
		 * One request thread of SLIME's: the requests it runs, innermost first, their
		 * outputs, its debugger levels and the mailbox the requests addressed to it wait
		 * in -- the REPL's queue for the REPL thread, one of its own for a worker. Its
		 * debugger levels run here ({@link #enter}).
		 */
		private final class Session implements CommonLispSwank.Sldb {

			/** What SWANK names the thread: {@code repl-thread} or {@code worker}. */
			private final String name;

			final BlockingQueue<Request> mailbox;

			final Deque<Pending> requests = new ArrayDeque<>();

			final Deque<Output> outputs = new ArrayDeque<>();

			final Deque<CommonLispSwank.Debugging> levels = new ArrayDeque<>();

			/**
			 * A debugger level that ended back into the one below it, which then shows
			 * itself again, as SWANK's {@code :sldb-return} event makes it.
			 */
			private int returned;

			Session(String name, BlockingQueue<Request> mailbox) {
				this.name = name;
				this.mailbox = mailbox;
			}

			/** The thread as SBCL prints it, in its abort restart's report. */
			String describe() {
				return "#<THREAD \"" + this.name + "\" RUNNING {"
						+ Long.toHexString(Thread.currentThread().threadId()).toUpperCase(Locale.ROOT) + "}>";
			}

			/**
			 * A debugger level, as SWANK's {@code sldb-loop}: announced to the editor,
			 * then the requests addressed to the thread answered until a restart
			 * transfers control out of it -- under a restart of its own, which a deeper
			 * level lists, that comes back here. Returns only when the connection closed.
			 */
			@Override
			public void enter(CommonLispSwank.Debugging debugging) {
				if (debugging.signalled()) {
					for (Pending pending : this.requests) {
						pending.condition = debugging;
					}
				}
				Output output = this.outputs.peek();
				if (output != null) {
					output.flush();
				}
				this.levels.push(debugging);
				int level = this.levels.size();
				long thread = Thread.currentThread().threadId();
				try {
					// what this level runs shows the level as a frame, as SWANK's
					// sldb-loop
					SwankServer.this.lisp.withFrame(lisp(List.of(new Symbol("swank::sldb-loop"), (long) level)), () -> {
						while (SwankServer.this.lisp.withRestart(LispNames.ABORT, "Return to sldb level " + level + ".",
								false, () -> answer(debugging, level, thread), () -> Boolean.TRUE)) {
							// this level's restart, or a deeper level ending: announced
							// again
						}
						return null;
					});
				}
				finally {
					this.levels.pop();
					this.returned = level > 1 ? level : 0;
					send(list(kw("debug-return"), thread, (long) level, null));
				}
			}

			/**
			 * Announces a level and answers the requests addressed to the thread: true to
			 * announce it again, false when the connection closed.
			 */
			private boolean answer(CommonLispSwank.Debugging debugging, int level, long thread) {
				List<Object> announce = new ArrayList<>(List.of(kw("debug"), thread, (long) level));
				announce.addAll(debuggerInfo(this, debugging, 0, INITIAL_FRAMES));
				send(announce);
				send(list(kw("debug-activate"), thread, (long) level, null));
				while (true) {
					if (this.returned == level + 1) {
						this.returned = 0;
						return true;
					}
					if (Connection.this.closed) {
						return false;
					}
					Request request;
					try {
						request = this.mailbox.poll(1, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						// an interrupt here enters a deeper level
						SwankServer.this.lisp.checkInterrupt();
						continue;
					}
					if (request != null) {
						run(request, null, this);
					}
				}
			}

		}

	}

	/**
	 * One of SLY's REPLs (Slynk's {@code mrepl}): a channel the editor sends its input to
	 * ({@code (:emacs-channel-send id (:process text))}) and a thread of its own that
	 * evaluates it in the REPL's package, answering on the editor's channel -- the
	 * output, the values or the condition the evaluation aborted on, then a prompt.
	 */
	private final class MRepl {

		private final Connection connection;

		private final long id;

		private final long remote;

		final Thread thread;

		/** The editor's channel messages and the requests naming this thread. */
		private final LinkedBlockingQueue<Object> events = new LinkedBlockingQueue<>();

		/** The REPL's package, which an evaluation that ends normally may change. */
		volatile String pkg;

		/** How many inputs answered values: the length of the REPL's history. */
		private int history;

		private volatile boolean closed;

		/** The output of the evaluation running, flushed before a read. */
		private volatile @Nullable Output output;

		MRepl(Connection connection, long id, long remote, String pkg) {
			this.connection = connection;
			this.id = id;
			this.remote = remote;
			this.pkg = pkg;
			this.thread = new Thread(null, this::loop, "slynk-mrepl-" + id, SwankServer.this.stackBytes);
			this.thread.setDaemon(true);
		}

		void post(Object event) {
			this.events.add(event);
		}

		/** Sends a message on the editor's channel. */
		void send(Object message) {
			this.connection.send(list(kw("channel-send"), this.remote, message));
		}

		/**
		 * Slynk's {@code prompt-arguments}: package, prompt name, debug level, history.
		 */
		List<Object> promptArguments() {
			CommonLispSwank lisp = SwankServer.this.lisp;
			return list(lisp.packageName(this.pkg), lisp.prompt(this.pkg), 0L, (long) this.history);
		}

		void prompt() {
			List<Object> message = new ArrayList<>();
			message.add(kw("prompt"));
			message.addAll(promptArguments());
			send(message);
		}

		private void loop() {
			while (!this.closed && !this.connection.closed) {
				try {
					Object event = this.events.poll(1, TimeUnit.SECONDS);
					if (event instanceof Request request) {
						this.connection.run(request, this, null);
					}
					else if (event instanceof List<?> message && !message.isEmpty()
							&& message.getFirst() instanceof Keyword kind) {
						switch (kind.name().toLowerCase(Locale.ROOT)) {
							case "process" -> eval(message.size() > 1 ? String.valueOf(message.get(1)) : "");
							case "clear-repl-history" -> {
								this.history = 0;
								SwankServer.this.lisp.clearReplVariables();
								send(list(kw("clear-repl-history")));
								prompt();
							}
							case "teardown" -> teardown();
							default -> {
								// an inspector's or a dedicated stream's message: not
								// served
							}
						}
					}
				}
				catch (InterruptedException ex) {
					// an interrupt that found no evaluation running: wait on
				}
			}
		}

		private void teardown() {
			this.closed = true;
			this.connection.channels.remove(this.id);
		}

		/** Slynk's {@code mrepl-eval}. */
		private void eval(String code) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			Output output = new Output(this.connection, this.thread.threadId(), SwankDialect.SLYNK, this);
			this.output = output;
			Outcome outcome;
			try {
				outcome = this.connection.execute(this.pkg, output, new Input(this::readLine), null, () -> {
					List<CommonLispSwank.ReplValue> values = lisp.mreplEval(code);
					this.pkg = lisp.currentPackage();
					return values;
				});
			}
			finally {
				this.output = null;
			}
			if (outcome == null) {
				return;
			}
			if (outcome.aborted() != null) {
				send(list(kw("evaluation-aborted"), outcome.aborted()));
			}
			else if (outcome.value() instanceof List<?> values) {
				if (!values.isEmpty()) {
					this.history++;
				}
				List<Object> results = new ArrayList<>();
				for (Object each : values) {
					if (each instanceof CommonLispSwank.ReplValue value) {
						results.add(list(value.presented(), (long) this.history - 1, value.copy()));
					}
				}
				send(list(kw("write-values"), results));
			}
			prompt();
		}

		/**
		 * A line of standard input from the REPL: the editor is told to read
		 * ({@code :set-read-mode :read}) and its next input is the line.
		 */
		@Nullable String readLine() {
			Output output = this.output;
			if (output != null) {
				output.flush();
			}
			send(list(kw("set-read-mode"), kw("read")));
			try {
				while (!this.closed && !this.connection.closed) {
					Object event = this.events.poll(1, TimeUnit.SECONDS);
					if (event instanceof Request request) {
						this.connection.run(request, this, null);
					}
					else if (event instanceof List<?> message && message.size() > 1
							&& message.getFirst() instanceof Keyword kind) {
						if (kind.name().equalsIgnoreCase("process") && message.get(1) instanceof String text) {
							return text;
						}
					}
					else if (event instanceof List<?> message && message.size() == 1
							&& message.getFirst() instanceof Keyword kind && kind.name().equalsIgnoreCase("teardown")) {
						teardown();
					}
				}
				return null;
			}
			catch (InterruptedException ex) {
				throw new LispInterruptSignal();
			}
			finally {
				send(list(kw("set-read-mode"), kw("finished-reading")));
			}
		}

	}

	/**
	 * A request's output, flushed when asked, when 64000 characters wait, and shortly
	 * after a write: SWANK's goes to the editor as {@code :write-string}, each flush
	 * waiting for the editor's {@code :write-done}; Slynk's to a SLY REPL's channel, or
	 * nowhere when the editor has none.
	 */
	private static final class Output extends Writer {

		private static final int LIMIT = 64000;

		private static final java.util.concurrent.ScheduledExecutorService FLUSHER = java.util.concurrent.Executors
			.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("swank-auto-flush").factory());

		private final Connection connection;

		private final long id;

		private final SwankDialect dialect;

		private final @Nullable MRepl channel;

		private final StringBuilder buffer = new StringBuilder();

		private boolean scheduled;

		/** The last character written, a newline before the first. */
		private char last = '\n';

		Output(Connection connection, long id, SwankDialect dialect, @Nullable MRepl channel) {
			this.connection = connection;
			this.id = id;
			this.dialect = dialect;
			this.channel = channel;
		}

		@Override
		public void write(char[] chars, int offset, int length) {
			boolean full;
			synchronized (this.buffer) {
				this.buffer.append(chars, offset, length);
				if (length > 0) {
					this.last = chars[offset + length - 1];
				}
				full = this.buffer.length() >= LIMIT;
				if (!full && !this.scheduled) {
					this.scheduled = true;
					// a flush waits for the editor: it runs on a thread of its own
					FLUSHER.schedule(() -> Thread.startVirtualThread(this::flush), 20, TimeUnit.MILLISECONDS);
				}
			}
			if (full) {
				flush();
			}
		}

		@Override
		public synchronized void flush() {
			String text;
			synchronized (this.buffer) {
				this.scheduled = false;
				if (this.buffer.isEmpty()) {
					return;
				}
				text = this.buffer.toString();
				this.buffer.setLength(0);
			}
			if (this.channel != null) {
				this.channel.send(list(kw("write-string"), text));
				return;
			}
			if (this.dialect == SwankDialect.MICROS) {
				// micros's output asks for no :write-done
				this.connection.send(list(kw("write-string"), text));
				return;
			}
			if (this.dialect != SwankDialect.SWANK) {
				return;
			}
			BlockingQueue<List<?>> box = new LinkedBlockingQueue<>();
			this.connection.writeDone.put(this.id, box);
			try {
				this.connection.send(list(kw("write-string"), text, null, this.id));
				while (!this.connection.closed) {
					if (box.poll(1, TimeUnit.SECONDS) != null) {
						return;
					}
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			finally {
				this.connection.writeDone.remove(this.id, box);
			}
		}

		/** Ends the line written so far, if any, as {@code fresh-line} does. */
		void freshLine() {
			synchronized (this.buffer) {
				if (this.last == '\n') {
					return;
				}
			}
			write(new char[] { '\n' }, 0, 1);
		}

		@Override
		public void close() {
			flush();
		}

	}

	/**
	 * A request's standard input: a read the buffer cannot answer takes the editor's next
	 * line ({@link Connection#readString}, {@link MRepl#readLine}, a minibuffer).
	 */
	private static final class Input extends Reader {

		private final Supplier<@Nullable String> lines;

		private String pending = "";

		private int pos;

		Input(Supplier<@Nullable String> lines) {
			this.lines = lines;
		}

		@Override
		public synchronized int read(char[] chars, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			if (this.pos >= this.pending.length()) {
				String line = this.lines.get();
				if (line == null || line.isEmpty()) {
					return -1;
				}
				this.pending = line;
				this.pos = 0;
			}
			int count = Math.min(length, this.pending.length() - this.pos);
			this.pending.getChars(this.pos, this.pos + count, chars, offset);
			this.pos += count;
			return count;
		}

		@Override
		public void close() {
			// the editor's input is not this reader's to close
		}

	}

	// ------------------------------------------------------------------------------------
	// Arguments and replies

	private static Keyword kw(String name) {
		return new Keyword(name);
	}

	private static List<Object> list(@Nullable Object... items) {
		return Arrays.asList(items);
	}

	/** An argument's value: a quoted datum unquoted, anything else as it reads. */
	private static @Nullable Object literal(@Nullable Object arg) {
		if (arg instanceof List<?> list && list.size() == 2 && list.getFirst() instanceof Symbol quote
				&& quote.name().equalsIgnoreCase("quote")) {
			return list.get(1);
		}
		return arg;
	}

	private static List<?> asList(@Nullable Object value) {
		return value instanceof List<?> list ? list
				: value == null || Boolean.FALSE.equals(value) ? List.of() : List.of(value);
	}

	private static boolean truthy(@Nullable Object value) {
		return value != null && !Boolean.FALSE.equals(value) && !(value instanceof List<?> list && list.isEmpty());
	}

	private static String text(List<Object> args, int index) {
		if (index < args.size() && args.get(index) instanceof String text) {
			return text;
		}
		throw new IllegalArgumentException("argument " + (index + 1) + " must be a string");
	}

	private static @Nullable String optionalText(List<Object> args, int index) {
		return index < args.size() && args.get(index) instanceof String text ? text : null;
	}

	private static int integer(List<Object> args, int index, int otherwise) {
		return index < args.size() && args.get(index) instanceof Number n ? n.intValue() : otherwise;
	}

	/** A keyword argument's value, the keywords starting at {@code from}. */
	private static @Nullable Object key(List<Object> args, int from, String name) {
		for (int i = from; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof Keyword keyword && keyword.name().equalsIgnoreCase(name)) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	/** The buffer position of a compile request's {@code ((:position P) (:line L C))}. */
	private static int position(@Nullable Object position) {
		for (Object entry : asList(position)) {
			if (entry instanceof List<?> pair && pair.size() > 1 && pair.getFirst() instanceof Keyword key
					&& key.name().equalsIgnoreCase("position") && pair.get(1) instanceof Number n) {
				return n.intValue();
			}
		}
		return 1;
	}

	private static String moduleName(Object module) {
		String name = switch (module) {
			case Keyword keyword -> keyword.name();
			case Symbol symbol -> symbol.member();
			default -> String.valueOf(module);
		};
		return name.toUpperCase(Locale.ROOT);
	}

	/** The request's dialect: the package of the function its form calls. */
	private static SwankDialect dialectOf(Object form) {
		SwankDialect dialect = form instanceof List<?> call && !call.isEmpty()
				&& call.getFirst() instanceof Symbol operator ? SwankDialect.of(operator.pkg()) : null;
		return dialect == null ? SwankDialect.SWANK : dialect;
	}

	/** Slynk's {@code longest-common-prefix}. */
	private static String commonPrefix(List<String> strings) {
		if (strings.isEmpty()) {
			return "";
		}
		String prefix = strings.getFirst();
		for (String each : strings) {
			int i = 0;
			while (i < prefix.length() && i < each.length() && prefix.charAt(i) == each.charAt(i)) {
				i++;
			}
			prefix = prefix.substring(0, i);
		}
		return prefix;
	}

	private static String directory(String path) {
		return path.endsWith("/") ? path : path + "/";
	}

	/**
	 * An autodoc form with the editor's cursor symbol made
	 * {@link CommonLispSwank#CURSOR}.
	 */
	private static List<Object> cursor(List<?> raw) {
		List<Object> out = new ArrayList<>(raw.size());
		for (Object element : raw) {
			if (element instanceof Symbol symbol && symbol.member().equals("%cursor-marker%")) {
				out.add(CommonLispSwank.CURSOR);
			}
			else if (element instanceof List<?> nested) {
				out.add(cursor(nested));
			}
			else {
				out.add(element instanceof String ? element : SwankWire.print(element));
			}
		}
		return out;
	}

	private static List<Object> completions(List<CommonLispSwank.Completion> candidates) {
		List<Object> out = new ArrayList<>(candidates.size());
		for (CommonLispSwank.Completion each : candidates) {
			out.add(each.qualified() == null ? List.of(each.candidate(), each.flags())
					: List.of(each.candidate(), each.flags(), each.qualified()));
		}
		return out;
	}

	/** A compilation as SWANK's {@code compilation-result}. */
	private static List<Object> compilation(CommonLispSwank.Compilation result, boolean loaded) {
		List<Object> notes = new ArrayList<>();
		for (CommonLispSwank.Note note : result.notes()) {
			notes.add(list(kw("message"), note.message(), kw("severity"), kw(note.severity()), kw("location"),
					location(note.location()), kw("references"), null));
		}
		return list(kw("compilation-result"), notes, result.success(), result.seconds(), loaded, null);
	}

	private static List<Object> location(@Nullable SwankLocation location) {
		return location(location, null);
	}

	private static List<Object> location(@Nullable SwankLocation location, @Nullable String snippet) {
		Object hints = snippet == null ? null : list(kw("snippet"), snippet);
		return switch (location) {
			case null -> list(kw("error"), "No location");
			case SwankLocation.Buffer buffer -> list(kw("location"), list(kw("buffer"), buffer.buffer()),
					list(kw("offset"), (long) buffer.start(), (long) buffer.offset()), hints);
			case SwankLocation.BufferAndFile buffer ->
				list(kw("location"), list(kw("buffer-and-file"), buffer.buffer(), buffer.file()),
						list(kw("offset"), (long) buffer.start(), (long) buffer.offset()), hints);
			case SwankLocation.File file -> list(kw("location"), list(kw("file"), file.file()),
					list(kw("position"), (long) file.position()), hints);
		};
	}

	/** Definitions or uses as SWANK's {@code xref>elisp}: {@code (label location)}. */
	private static List<Object> entries(List<SwankSources.Entry> entries) {
		List<Object> out = new ArrayList<>(entries.size());
		for (SwankSources.Entry entry : entries) {
			SwankSources.Place place = entry.place();
			out.add(list(entry.label(),
					place == null ? list(kw("error"), entry.error()) : location(place.location(), place.snippet())));
		}
		return out;
	}

	/** An inspector page as SWANK's {@code istate>elisp}. */
	private static @Nullable List<Object> page(SwankInspector.@Nullable Page page) {
		return page == null ? null
				: list(kw("title"), page.title(), kw("id"), (long) page.id(), kw("content"), range(page.content()));
	}

	private static List<Object> range(SwankInspector.Range range) {
		List<Object> parts = new ArrayList<>(range.parts().size());
		for (SwankInspector.Part part : range.parts()) {
			parts.add(switch (part) {
				case SwankInspector.Text text -> text.text();
				case SwankInspector.Value value -> list(kw("value"), value.text(), (long) value.index());
				case SwankInspector.Action action -> list(kw("action"), action.label(), (long) action.index());
			});
		}
		return list(parts, (long) range.next(), (long) range.start(), (long) range.end());
	}

	/**
	 * A Lisp value as a reply carries it: a string, a number, a symbol, nil, t and a
	 * proper list of those as themselves, anything else as {@code prin1} prints it.
	 */
	private static @Nullable Object wire(LispVal value, CommonLispSwank lisp) {
		return switch (value) {
			case LispNil nil -> null;
			case LispTrue t -> Boolean.TRUE;
			case LispString s -> s.value();
			case LispInteger i -> i.value();
			case am.ik.rontolisp.LispBigInteger big -> big.value();
			case am.ik.rontolisp.LispDouble d -> d.value();
			case LispSymbol symbol when symbol.isKeyword() -> kw(symbol.name().substring(1));
			case LispSymbol symbol -> new Symbol(symbol.name());
			case LispCons cons when cons.isProperList() -> {
				List<Object> out = new ArrayList<>();
				for (LispVal rest = cons; rest instanceof LispCons cell; rest = cell.cdr()) {
					out.add(wire(cell.car(), lisp));
				}
				yield out;
			}
			default -> lisp.print(value);
		};
	}

	/** A request's form as Lisp data, its symbols as the Lisp reader would name them. */
	private static LispVal lisp(@Nullable Object value) {
		return switch (value) {
			case null -> LispNil.INSTANCE;
			case Boolean b -> b ? LispTrue.INSTANCE : LispNil.INSTANCE;
			case String s -> LispString.literal(s);
			case Long l -> new LispInteger(l);
			case BigInteger big -> new am.ik.rontolisp.LispBigInteger(big);
			case Double d -> new am.ik.rontolisp.LispDouble(d);
			case Keyword keyword -> new LispSymbol(":" + keyword.name().toUpperCase(Locale.ROOT));
			case Symbol symbol -> new LispSymbol(symbol.name().toUpperCase(Locale.ROOT));
			case List<?> list -> {
				LispVal out = LispNil.INSTANCE;
				for (int i = list.size() - 1; i >= 0; i--) {
					out = new LispCons(lisp(list.get(i)), out);
				}
				yield out;
			}
			default -> LispString.literal(String.valueOf(value));
		};
	}

}
