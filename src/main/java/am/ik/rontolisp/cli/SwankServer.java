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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.LispInterruptSignal;

/**
 * The SWANK server {@code rontolisp --swank} runs: SLIME's protocol over TCP
 * ({@link SwankWire}), answering the requests of SLIME 2.32 and its {@code slime-fancy}
 * contribs over the interpreter ({@link CommonLispSwank}), in the shapes SWANK 2.32 on
 * SBCL answers them.
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
 * An error no handler handles enters SWANK's debugger on the thread it was signalled on,
 * before anything unwinds ({@code :debug}, {@code :debug-activate}): the thread answers
 * the requests the editor's SLDB sends it until one invokes a restart, the program's or
 * one a request established (SWANK's top level, a retry, a debugger level), and announces
 * the level's end ({@code :debug-return}). An {@code :emacs-interrupt} enters it where it
 * stops the newest request's evaluation, a {@code CONTINUE} restart going on with it.
 */
final class SwankServer implements AutoCloseable {

	/**
	 * The version of the protocol the server answers: SLIME's release it was measured
	 * against.
	 */
	static final String PROTOCOL_VERSION = "2.32";

	/**
	 * The contribs the server answers for, which {@code connection-info} reports loaded.
	 */
	private static final List<String> MODULES = List.of("SWANK-REPL", "SWANK-ARGLISTS", "SWANK-C-P-C", "SWANK-FUZZY",
			"SWANK-UTIL");

	/**
	 * How many frames a debugger level announces, SWANK's {@code *sldb-initial-frames*}.
	 */
	private static final int INITIAL_FRAMES = 20;

	private final CommonLispSwank lisp;

	private final LispEvaluator evaluator;

	private final ServerSocket server;

	private final long stackBytes;

	private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

	private final Set<String> modules = Collections.synchronizedSet(new LinkedHashSet<>(MODULES));

	private volatile @Nullable LispExitSignal exit;

	/**
	 * A server listening on a port.
	 * @param lisp the Common Lisp half every request evaluates through
	 * @param evaluator the evaluator it runs on
	 * @param bind the address to listen on
	 * @param port the port, 0 for any free one
	 * @param stackBytes the stack of each request's thread
	 * @throws IOException when the port cannot be listened on
	 */
	SwankServer(CommonLispSwank lisp, LispEvaluator evaluator, InetAddress bind, int port, long stackBytes)
			throws IOException {
		this.lisp = lisp;
		this.evaluator = evaluator;
		this.stackBytes = stackBytes;
		this.server = new ServerSocket(port, 50, bind);
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
		while (!this.server.isClosed()) {
			Socket socket;
			try {
				socket = this.server.accept();
			}
			catch (SocketException ex) {
				if (this.server.isClosed()) {
					break;
				}
				throw ex;
			}
			Connection connection = new Connection(socket);
			this.connections.add(connection);
			Thread.ofVirtual().name("swank-reader").start(connection);
		}
		LispExitSignal asked = this.exit;
		if (asked != null) {
			throw asked;
		}
	}

	@Override
	public void close() {
		try {
			this.server.close();
		}
		catch (IOException ex) {
			// closing anyway
		}
		for (Connection connection : this.connections) {
			connection.close();
		}
	}

	/** One {@code :emacs-rex} request. */
	private static final class Request {

		final Object form;

		final @Nullable String pkg;

		final Object id;

		/**
		 * The last condition a debugger level was entered for while the request ran, what
		 * its {@code :abort} reports, as SWANK's {@code eval-for-emacs} records every
		 * condition signalled in its extent.
		 */
		volatile CommonLispSwank.@Nullable Debugging condition;

		Request(Object form, @Nullable String pkg, Object id) {
			this.form = form;
			this.pkg = pkg;
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

	/** A request a thread is running, which an interrupt can stop. */
	private record Running(Thread thread, Request request) {
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

		private final AtomicLong tags = new AtomicLong();

		private volatile boolean closed;

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
					if (thread instanceof Keyword keyword && keyword.name().equalsIgnoreCase("repl-thread")
							|| thread instanceof Number id && repl != null && id.longValue() == repl.threadId()) {
						// queued first: a REPL thread ending now sees it and starts the
						// next
						this.replQueue.add(request);
						startRepl();
					}
					else if (thread instanceof Number id
							&& this.workers.get(id.longValue()) instanceof Session worker) {
						// a worker's debugger level answers what its SLDB sends it
						worker.mailbox.add(request);
					}
					else if (thread instanceof Number id) {
						send(list(kw("invalid-rpc"), request.id, "Thread not found: " + id));
					}
					else {
						Thread worker = new Thread(null, () -> {
							long self = Thread.currentThread().threadId();
							Session session = new Session("worker", new LinkedBlockingQueue<>());
							this.workers.put(self, session);
							try {
								run(session, request);
							}
							finally {
								this.workers.remove(self);
							}
						}, "swank-worker", SwankServer.this.stackBytes);
						worker.setDaemon(true);
						worker.start();
					}
				}
				case "emacs-interrupt" -> interrupt(event.size() > 1 ? event.get(1) : Boolean.TRUE);
				case "write-done" -> deliver(this.writeDone, event);
				case "emacs-return-string" -> deliver(this.input, event);
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
						if (request != null && run(session, request) == Unwound.THREAD) {
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
			for (Running each : this.running) {
				if (!(target instanceof Number id) || each.thread().threadId() == id.longValue()) {
					SwankServer.this.evaluator.interrupt(each.thread());
					return;
				}
			}
		}

		/**
		 * Runs a request on the calling thread and answers it: one the thread starts with
		 * under SWANK's top-level restarts, one a debugger level runs inside the
		 * evaluation it stopped. A restart invoked further out passes on, the request
		 * answering {@code :abort} first.
		 * @return how a top-level restart ended it, or null when none did
		 */
		@Nullable Unwound run(Session session, Request request) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			Thread self = Thread.currentThread();
			boolean topLevel = session.requests.isEmpty();
			Running running = new Running(self, request);
			this.running.addFirst(running);
			Output output = new Output(this, self.threadId());
			Input reader = new Input(this, self.threadId(), lisp);
			session.requests.push(request);
			session.outputs.push(output);
			Runnable restore = lisp.controlState();
			Object reply;
			Unwound unwound = null;
			try {
				String pkg = lisp.guessPackage(request.pkg);
				Object value = lisp.serve(pkg, output, reader, session,
						() -> lisp.withFrame(lisp(request.form), topLevel ? () -> topLevel(session, request, output)
								: () -> call(request.form, output, session)));
				output.flush();
				if (value instanceof Unwound how) {
					unwound = how;
					reply = aborted(request);
				}
				else {
					reply = list(kw("return"), list(kw("ok"), value), request.id);
				}
			}
			catch (LispInterruptSignal ex) {
				// what it printed before it stopped still reaches the editor
				output.flush();
				reply = list(kw("return"),
						list(kw("abort"), "#<SIMPLE-ERROR \"" + CommonLispSwank.INTERRUPT_MESSAGE + "\">"), request.id);
			}
			catch (LispExitSignal ex) {
				// the oracle's quit ends its process, and the editor's connection with it
				output.flush();
				SwankServer.this.exit = ex;
				SwankServer.this.close();
				return null;
			}
			catch (StackOverflowError ex) {
				restore.run();
				output.flush();
				reply = list(kw("return"), list(kw("abort"), lisp.describeFailure(ex)), request.id);
			}
			catch (RuntimeException ex) {
				output.flush();
				if (lisp.isTransfer(ex)) {
					// a restart further out: this request ends on its way there
					send(aborted(request));
					throw ex;
				}
				reply = list(kw("return"), list(kw("abort"), lisp.describeFailure(ex)), request.id);
			}
			finally {
				session.requests.pop();
				session.outputs.pop();
				this.running.remove(running);
				SwankServer.this.evaluator.clearInterrupt();
			}
			send(reply);
			return unwound;
		}

		/**
		 * A request's {@code :abort}: the last condition it entered a debugger for, or
		 * nil.
		 */
		private List<Object> aborted(Request request) {
			CommonLispSwank.Debugging condition = request.condition;
			return list(kw("return"), list(kw("abort"), condition == null ? "NIL" : condition.describe()), request.id);
		}

		/**
		 * A request the thread starts with, under SWANK's top-level restarts: the
		 * thread's own abort, and inside it the quit restart {@code throw-to-toplevel}
		 * invokes.
		 */
		private Object topLevel(Session session, Request request, Output output) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			return lisp
				.withRestart(LispNames.ABORT, "abort thread (" + session.describe() + ")", false,
						() -> lisp.withRestart(LispNames.ABORT, "Return to SLIME's top level.", true,
								() -> call(request.form, output, session), () -> Unwound.TOP_LEVEL),
						() -> Unwound.THREAD);
		}

		/**
		 * Answers a request's form: a function SWANK defines, else the form evaluated.
		 */
		private @Nullable Object call(Object form, Output output, Session session) {
			if (!(form instanceof List<?> call) || call.isEmpty() || !(call.getFirst() instanceof Symbol operator)) {
				throw new IllegalArgumentException("not a function call: " + SwankWire.print(form));
			}
			List<Object> args = new ArrayList<>();
			for (Object arg : call.subList(1, call.size())) {
				args.add(literal(arg));
			}
			if (!operator.pkg().startsWith("swank")) {
				return evaluate(call);
			}
			CommonLispSwank lisp = SwankServer.this.lisp;
			return switch (operator.member()) {
				case "connection-info" -> connectionInfo();
				case "swank-require" -> {
					for (Object module : args.isEmpty() ? List.of() : asList(args.getFirst())) {
						SwankServer.this.modules.add(moduleName(module));
					}
					yield List.copyOf(SwankServer.this.modules);
				}
				case "create-repl" -> {
					startRepl();
					yield List.of(lisp.packageName(lisp.currentPackage()), lisp.prompt(lisp.currentPackage()));
				}
				case "listener-eval" ->
					retrying("Retry SLIME REPL evaluation request.", () -> listenerEval(text(args, 0), output));
				case "interactive-eval" -> retrying("Retry SLIME interactive evaluation request.",
						() -> lisp.interactiveEval(text(args, 0), integer(args, 1, 1), integer(args, 2, 80)));
				case "interactive-eval-region" -> retrying("Retry SLIME interactive evaluation request.",
						() -> lisp.interactiveEvalRegion(text(args, 0), integer(args, 1, 1), integer(args, 2, 80)));
				case "eval-and-grab-output" ->
					retrying("Retry SLIME evaluation request.", () -> lisp.evalAndGrabOutput(text(args, 0)));
				case "pprint-eval" -> lisp.pprintEval(text(args, 0));
				case "re-evaluate-defvar" ->
					retrying("Retry SLIME evaluation request.", () -> lisp.reEvaluateDefvar(text(args, 0)));
				// SLDB: what the debugger level running on this thread answers
				case "throw-to-toplevel" -> lisp.throwToToplevel();
				case "invoke-nth-restart-for-emacs" -> session.level() instanceof CommonLispSwank.Debugging level
						&& integer(args, 0, -1) == session.levels.size()
								? wireOrNil(level.invokeNth(integer(args, 1, -1))) : null;
				case "invoke-nth-restart" -> session.level() instanceof CommonLispSwank.Debugging level
						? wireOrNil(level.invokeNth(integer(args, 0, -1))) : null;
				case "sldb-abort" -> session.level() instanceof CommonLispSwank.Debugging level
						? wireOrNil(level.invokeNamed(LispNames.ABORT)) : null;
				case "sldb-continue" -> session.level() instanceof CommonLispSwank.Debugging level
						? wireOrNil(level.invokeNamed(LispNames.CONTINUE)) : null;
				case "backtrace" -> session.level() instanceof CommonLispSwank.Debugging level
						? backtrace(level, integer(args, 0, 0), integer(args, 1, Integer.MAX_VALUE)) : List.of();
				case "debugger-info-for-emacs" -> session.level() instanceof CommonLispSwank.Debugging level
						? debuggerInfo(session, level, integer(args, 0, 0), integer(args, 1, Integer.MAX_VALUE)) : null;
				case "frame-locals-and-catch-tags" -> {
					List<Object> locals = new ArrayList<>();
					int index = integer(args, 0, -1);
					if (session.level() instanceof CommonLispSwank.Debugging level && index >= 0
							&& index < level.frameCount()) {
						for (CommonLispSwank.Local local : level.locals(index)) {
							locals.add(list(kw("name"), local.name(), kw("id"), (long) local.id(), kw("value"),
									local.value()));
						}
					}
					yield Arrays.asList(locals, null);
				}
				case "frame-package-name" -> session.level() instanceof CommonLispSwank.Debugging level
						? level.framePackage(integer(args, 0, 0)) : lisp.packageName(lisp.currentPackage());
				case "eval-string-in-frame" -> session.level() instanceof CommonLispSwank.Debugging level
						? level.evalInFrame(text(args, 0), integer(args, 1, 0), optionalText(args, 2),
								integer(args, 3, 1), integer(args, 4, 80))
						: lisp.interactiveEval(text(args, 0), integer(args, 3, 1), integer(args, 4, 80));
				case "pprint-eval-string-in-frame" -> session.level() instanceof CommonLispSwank.Debugging level
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
						position(args.size() > 2 ? args.get(2) : null)), false);
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
				case "find-definitions-for-emacs", "xref", "xrefs" -> null;
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

		private @Nullable Object listenerEval(String code, Output output) {
			CommonLispSwank lisp = SwankServer.this.lisp;
			String before = lisp.currentPackage();
			List<String> values = lisp.listenerEval(code);
			// the presentation contrib's REPL results start on a line of their own
			output.freshLine();
			output.flush();
			if (values.isEmpty()) {
				send(list(kw("write-string"), "; No value", kw("repl-result")));
			}
			for (String value : values) {
				send(list(kw("write-string"), value + "\n", kw("repl-result")));
			}
			String after = lisp.currentPackage();
			if (!after.equals(before)) {
				send(list(kw("new-package"), lisp.packageName(after), lisp.prompt(after)));
			}
			return null;
		}

		private List<Object> connectionInfo() {
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
					kw("features"), features, kw("modules"), List.copyOf(SwankServer.this.modules), kw("package"),
					list(kw("name"), lisp.packageName(pkg), kw("prompt"), lisp.prompt(pkg)), kw("version"),
					PROTOCOL_VERSION);
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
		 * Runs a request's body under a retry restart, as SWANK's
		 * {@code with-retry-restart}: invoking it runs the body again.
		 */
		private @Nullable Object retrying(String report, Supplier<@Nullable Object> body) {
			while (true) {
				Object value = SwankServer.this.lisp.withRestart("SWANK::RETRY", report, false, body,
						() -> Retry.AGAIN);
				if (value != Retry.AGAIN) {
					return value;
				}
			}
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
			for (Request request : session.requests) {
				continuations.add(request.id);
			}
			return list(list(level.message(), "   [Condition of type " + level.conditionType() + "]", null), restarts,
					backtrace(level, start, end), continuations);
		}

		/**
		 * One request thread: the requests it runs, innermost first, their outputs, its
		 * debugger levels and the mailbox the requests addressed to it wait in -- the
		 * REPL's queue for the REPL thread, one of its own for a worker. Its debugger
		 * levels run here ({@link #enter}).
		 */
		private final class Session implements CommonLispSwank.Sldb {

			/** What SWANK names the thread: {@code repl-thread} or {@code worker}. */
			private final String name;

			final BlockingQueue<Request> mailbox;

			final Deque<Request> requests = new ArrayDeque<>();

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

			/** The innermost debugger level running, or null. */
			CommonLispSwank.@Nullable Debugging level() {
				return this.levels.peek();
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
					for (Request request : this.requests) {
						request.condition = debugging;
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
						run(this, request);
					}
				}
			}

		}

	}

	/** What a retry restart's invocation answers, to run the body again. */
	private enum Retry {

		AGAIN

	}

	/**
	 * A request's output: what it writes goes to the editor as {@code :write-string},
	 * flushed when asked, when 64000 characters wait, and shortly after a write, each
	 * flush waiting for the editor's {@code :write-done} as SWANK's does.
	 */
	private static final class Output extends Writer {

		private static final int LIMIT = 64000;

		private static final java.util.concurrent.ScheduledExecutorService FLUSHER = java.util.concurrent.Executors
			.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("swank-auto-flush").factory());

		private final Connection connection;

		private final long id;

		private final StringBuilder buffer = new StringBuilder();

		private boolean scheduled;

		/** The last character written, a newline before the first. */
		private char last = '\n';

		Output(Connection connection, long id) {
			this.connection = connection;
			this.id = id;
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
	 * A request's standard input: a read the buffer cannot answer asks the editor for a
	 * line ({@code :read-string}) and waits for it ({@code :emacs-return-string}).
	 */
	private static final class Input extends Reader {

		private final Connection connection;

		private final long id;

		private final CommonLispSwank lisp;

		private String pending = "";

		private int pos;

		Input(Connection connection, long id, CommonLispSwank lisp) {
			this.connection = connection;
			this.id = id;
			this.lisp = lisp;
		}

		@Override
		public synchronized int read(char[] chars, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			if (this.pos >= this.pending.length() && !fill()) {
				return -1;
			}
			int count = Math.min(length, this.pending.length() - this.pos);
			this.pending.getChars(this.pos, this.pos + count, chars, offset);
			this.pos += count;
			return count;
		}

		private boolean fill() throws IOException {
			long tag = this.connection.nextTag();
			BlockingQueue<List<?>> box = new LinkedBlockingQueue<>();
			this.connection.input.put(this.id, box);
			this.connection.send(list(kw("read-string"), this.id, tag));
			boolean read = false;
			try {
				while (true) {
					List<?> event;
					try {
						event = box.poll(1, TimeUnit.SECONDS);
					}
					catch (InterruptedException ex) {
						// the interrupt enters the debugger, and its continue reads on
						this.lisp.checkInterrupt();
						continue;
					}
					if (event != null) {
						read = true;
						if (event.size() > 3 && event.get(3) instanceof String line) {
							this.pending = line;
							this.pos = 0;
							return !line.isEmpty();
						}
						return false;
					}
					if (this.connection.closed) {
						read = true;
						return false;
					}
				}
			}
			finally {
				this.connection.input.remove(this.id, box);
				if (!read) {
					// left by an interrupt or a restart: the editor stops reading
					this.connection.send(list(kw("read-aborted"), this.id, tag));
				}
			}
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

	private static List<Object> location(CommonLispSwank.@Nullable Location location) {
		return switch (location) {
			case null -> list(kw("error"), "No location");
			case CommonLispSwank.Location.Buffer buffer -> list(kw("location"), list(kw("buffer"), buffer.buffer()),
					list(kw("offset"), (long) buffer.start(), (long) buffer.offset()), null);
			case CommonLispSwank.Location.File file ->
				list(kw("location"), list(kw("file"), file.file()), list(kw("position"), (long) file.position()), null);
		};
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
