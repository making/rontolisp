package am.ik.rontolisp.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.Version;
import am.ik.rontolisp.eval.ClojureNrepl;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.LispExitSignal;
import am.ik.rontolisp.eval.LispInterruptSignal;

/**
 * The nREPL server {@code rontolisp --nrepl} runs: bencode messages over TCP, answering
 * the ops of nREPL 1.3's default handler an editor needs -- {@code describe},
 * {@code clone}, {@code close}, {@code ls-sessions}, {@code eval}, {@code load-file},
 * {@code interrupt}, {@code completions} -- over the interpreter's run-time lowering
 * ({@link ClojureNrepl}), in the shapes the oracle answers.
 *
 * <p>
 * Every connection shares the sessions, as the oracle's do. A session evaluates its
 * messages one after the other on a thread of its own, sized like the command line's; a
 * message naming no session runs on a thread of its own with fresh variables. An
 * {@code interrupt} answers the running message {@code interrupted} at once and asks its
 * evaluation to stop ({@link LispEvaluator#interrupt}), and the session goes on on a
 * fresh thread, as the oracle's does: whatever the old one still does is never answered.
 */
final class NreplServer implements AutoCloseable {

	/** The version of the nREPL protocol whose ops and shapes the server answers. */
	private static final Map<String, Object> NREPL_VERSION = Map.of("major", 1L, "minor", 3L, "incremental", 1L,
			"version-string", "1.3.1");

	private static final List<String> OPS = List.of("clone", "close", "completions", "describe", "eval", "interrupt",
			"load-file", "ls-sessions");

	private final ClojureNrepl clojure;

	private final LispEvaluator evaluator;

	private final ServerSocket server;

	private final long stackBytes;

	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

	private volatile @Nullable LispExitSignal exit;

	/**
	 * A server listening on a port.
	 * @param clojure the Clojure half every session evaluates through
	 * @param evaluator the evaluator it runs on
	 * @param bind the address to listen on
	 * @param port the port, 0 for any free one
	 * @param stackBytes the stack of each session's thread
	 * @throws IOException when the port cannot be listened on
	 */
	NreplServer(ClojureNrepl clojure, LispEvaluator evaluator, InetAddress bind, int port, long stackBytes)
			throws IOException {
		this.clojure = clojure;
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
	 * The line the oracle's command line prints once it listens, which editors (CIDER)
	 * parse for the host and the port.
	 * @return the line
	 */
	String startedMessage() {
		String host = this.server.getInetAddress().getHostName();
		return "nREPL server started on port " + port() + " on host " + host + " - nrepl://" + host + ":" + port();
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
			Thread.ofVirtual().name("nREPL-connection").start(connection);
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
		for (Session session : this.sessions.values()) {
			session.close();
		}
		this.sessions.clear();
	}

	/**
	 * One client's socket: its messages read on one thread, its replies written whole.
	 */
	private final class Connection implements Runnable {

		private final Socket socket;

		private final OutputStream out;

		Connection(Socket socket) throws IOException {
			this.socket = socket;
			this.out = new BufferedOutputStream(socket.getOutputStream());
		}

		@Override
		public void run() {
			try (InputStream in = new BufferedInputStream(this.socket.getInputStream())) {
				for (Object message = Bencode.read(in); message != null; message = Bencode.read(in)) {
					if (message instanceof Map<?, ?> map) {
						handle(this, strings(map));
					}
				}
			}
			catch (IOException ex) {
				// the client went away, or sent no bencode: the connection ends
			}
			finally {
				close();
				NreplServer.this.connections.remove(this);
			}
		}

		void send(Map<String, Object> reply) {
			synchronized (this.out) {
				try {
					Bencode.write(this.out, reply);
				}
				catch (IOException ex) {
					close();
				}
			}
		}

		void close() {
			try {
				this.socket.close();
			}
			catch (IOException ex) {
				// closing anyway
			}
		}

	}

	private void handle(Connection connection, Map<String, Object> message) {
		String requested = text(message, "op");
		String op = requested == null ? "" : requested;
		String named = text(message, "session");
		Session session = named == null ? null : this.sessions.get(named);
		if (named != null && session == null) {
			connection.send(reply(message, named, "status", List.of("done", "unknown-session", "error")));
			return;
		}
		// a message naming no session runs in one made for it, which the reply names
		String sessionId = named != null ? named : UUID.randomUUID().toString();
		switch (op) {
			case "describe" -> connection.send(describe(message, sessionId, session));
			case "clone" -> {
				Session fresh = new Session(session == null ? this.clojure.newBindings() : session.bindings.copy(),
						false);
				this.sessions.put(fresh.id, fresh);
				Map<String, Object> reply = reply(message, sessionId, "new-session", fresh.id);
				reply.put("status", List.of("done"));
				connection.send(reply);
			}
			case "close" -> {
				if (session != null) {
					this.sessions.remove(session.id);
					session.close();
				}
				connection.send(reply(message, sessionId, "status", List.of("done", "session-closed")));
			}
			case "ls-sessions" -> {
				Map<String, Object> reply = reply(message, sessionId, "sessions",
						new ArrayList<>(this.sessions.keySet()));
				reply.put("status", List.of("done"));
				connection.send(reply);
			}
			case "interrupt" -> interrupt(connection, message, sessionId, session);
			case "eval", "load-file" -> {
				if (op.equals("eval") && text(message, "code") == null
						|| op.equals("load-file") && text(message, "file") == null) {
					connection.send(reply(message, sessionId, "status",
							List.of("error", op.equals("eval") ? "no-code" : "no-file", "done")));
					return;
				}
				Session runs = session != null ? session : new Session(this.clojure.newBindings(), true);
				runs.submit(new Task(connection, message, runs));
			}
			case "completions" -> completions(connection, message, sessionId, session);
			default -> {
				Map<String, Object> reply = reply(message, sessionId, "status", List.of("done", "unknown-op", "error"));
				if (requested != null) {
					reply.put("op", requested);
				}
				connection.send(reply);
			}
		}
	}

	private Map<String, Object> describe(Map<String, Object> message, String sessionId, @Nullable Session session) {
		Map<String, Object> ops = new TreeMap<>();
		for (String op : OPS) {
			ops.put(op, Map.of());
		}
		String[] clojure = this.clojure.clojureVersion().split("\\.");
		Map<String, Object> versions = new LinkedHashMap<>();
		versions.put("clojure", Map.of("major", Long.parseLong(clojure[0]), "minor", Long.parseLong(clojure[1]),
				"incremental", Long.parseLong(clojure[2]), "version-string", this.clojure.clojureVersion()));
		versions.put("java", Map.of("major", (long) Runtime.version().feature(), "version-string",
				System.getProperty("java.version", "")));
		versions.put("nrepl", NREPL_VERSION);
		versions.put("rontolisp", Map.of("version-string", Version.getVersion()));
		Map<String, Object> reply = reply(message, sessionId, "ops", ops);
		reply.put("versions", versions);
		reply.put("aux", Map.of("current-ns",
				session == null ? this.clojure.newBindings().namespace() : session.bindings.namespace()));
		reply.put("status", List.of("done"));
		return reply;
	}

	private void interrupt(Connection connection, Map<String, Object> message, String sessionId,
			@Nullable Session session) {
		if (session == null) {
			connection.send(reply(message, sessionId, "status", List.of("error", "session-ephemeral", "done")));
			return;
		}
		String interruptId = text(message, "interrupt-id");
		Session.Interrupted outcome = session.interrupt(interruptId);
		switch (outcome) {
			case Session.Interrupted.Idle _ ->
				connection.send(reply(message, session.id, "status", List.of("done", "session-idle")));
			case Session.Interrupted.Mismatch _ -> connection
				.send(reply(message, session.id, "status", List.of("error", "interrupt-id-mismatch", "done")));
			case Session.Interrupted.Stopped stopped -> {
				Map<String, Object> interrupted = new LinkedHashMap<>();
				if (stopped.task().id != null) {
					interrupted.put("id", stopped.task().id);
				}
				interrupted.put("session", session.id);
				interrupted.put("status", List.of("interrupted", "done"));
				stopped.task().connection.send(interrupted);
				connection.send(reply(message, session.id, "status", List.of("done")));
			}
		}
	}

	private void completions(Connection connection, Map<String, Object> message, String sessionId,
			@Nullable Session session) {
		String prefix = text(message, "prefix");
		String ns = text(message, "ns");
		if (ns == null) {
			ns = session == null ? this.clojure.newBindings().namespace() : session.bindings.namespace();
		}
		List<Object> candidates = new ArrayList<>();
		for (ClojureNrepl.Completion completion : this.clojure.completions(prefix == null ? "" : prefix, ns)) {
			Map<String, Object> candidate = new LinkedHashMap<>();
			candidate.put("candidate", completion.candidate());
			candidate.put("type", completion.type());
			if (completion.ns() != null) {
				candidate.put("ns", completion.ns());
			}
			candidates.add(candidate);
		}
		Map<String, Object> reply = reply(message, sessionId, "completions", candidates);
		reply.put("status", List.of("done"));
		connection.send(reply);
	}

	/** A reply to a message: its {@code id}, the session, and one slot. */
	private static Map<String, Object> reply(Map<String, Object> message, @Nullable String session, String key,
			Object value) {
		Map<String, Object> reply = reply(message, session);
		reply.put(key, value);
		return reply;
	}

	/** A reply to a message: its {@code id} and the session. */
	private static Map<String, Object> reply(Map<String, Object> message, @Nullable String session) {
		Map<String, Object> reply = new LinkedHashMap<>();
		Object id = message.get("id");
		if (id != null) {
			reply.put("id", id);
		}
		if (session != null) {
			reply.put("session", session);
		}
		return reply;
	}

	private static @Nullable String text(Map<String, Object> message, String key) {
		return message.get(key) instanceof String value ? value : null;
	}

	private static Map<String, Object> strings(Map<?, ?> map) {
		Map<String, Object> out = new LinkedHashMap<>();
		map.forEach((key, value) -> out.put(String.valueOf(key), value));
		return out;
	}

	/**
	 * A session: the variables its messages run under, and the thread running them in
	 * order.
	 */
	private final class Session {

		/** What an interrupt found. */
		sealed interface Interrupted {

			/** Nothing was running. */
			record Idle() implements Interrupted {
			}

			/** Something else was running than the message the interrupt named. */
			record Mismatch() implements Interrupted {
			}

			/**
			 * The running message, now answered {@code interrupted}.
			 *
			 * @param task the message
			 */
			record Stopped(Task task) implements Interrupted {
			}

		}

		final String id = UUID.randomUUID().toString();

		/** The variables, as the last message that ran through left them. */
		volatile ClojureNrepl.Bindings bindings;

		private final boolean ephemeral;

		private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();

		private @Nullable Thread worker;

		private @Nullable Task running;

		private boolean closed;

		Session(ClojureNrepl.Bindings bindings, boolean ephemeral) {
			this.bindings = bindings;
			this.ephemeral = ephemeral;
		}

		synchronized void submit(Task task) {
			if (this.closed) {
				return;
			}
			this.queue.add(task);
			if (this.worker == null) {
				startWorker();
			}
		}

		private void startWorker() {
			Thread thread = new Thread(null, this::work, "nREPL-session-" + this.id, NreplServer.this.stackBytes);
			thread.setDaemon(true);
			this.worker = thread;
			thread.start();
		}

		private void work() {
			Thread self = Thread.currentThread();
			while (true) {
				Task task;
				try {
					task = this.ephemeral ? this.queue.poll() : this.queue.take();
				}
				catch (InterruptedException ex) {
					task = null;
				}
				synchronized (this) {
					if (this.worker != self) {
						// closed, the queue cleared with it
						return;
					}
					if (task == null) {
						if (this.ephemeral) {
							this.worker = null;
							return;
						}
						continue;
					}
					this.running = task;
				}
				try {
					task.run();
				}
				finally {
					// no interrupt names the task once it is no longer running, so the
					// clear behind drops whatever one did
					synchronized (this) {
						if (this.running == task) {
							this.running = null;
						}
					}
					NreplServer.this.evaluator.clearInterrupt();
				}
				synchronized (this) {
					if (this.worker != self) {
						// an interrupt handed the session to a fresh thread
						return;
					}
				}
			}
		}

		synchronized Interrupted interrupt(@Nullable String interruptId) {
			Task task = this.running;
			Thread thread = this.worker;
			if (task == null || thread == null) {
				return new Interrupted.Idle();
			}
			if (interruptId != null && !interruptId.equals(task.id)) {
				return new Interrupted.Mismatch();
			}
			task.stop();
			NreplServer.this.evaluator.interrupt(thread);
			this.running = null;
			startWorker();
			return new Interrupted.Stopped(task);
		}

		synchronized void close() {
			this.closed = true;
			this.queue.clear();
			Thread thread = this.worker;
			this.worker = null;
			if (thread != null) {
				if (this.running != null) {
					this.running.stop();
					NreplServer.this.evaluator.interrupt(thread);
				}
				else {
					thread.interrupt();
				}
			}
		}

	}

	/** One {@code eval} or {@code load-file} message, run on its session's thread. */
	private final class Task {

		final @Nullable Object id;

		final Connection connection;

		private final Map<String, Object> message;

		private final Session session;

		private boolean stopped;

		Task(Connection connection, Map<String, Object> message, Session session) {
			this.id = message.get("id");
			this.connection = connection;
			this.message = message;
			this.session = session;
		}

		/** Answers nothing more: the message was interrupted, and answered so. */
		synchronized void stop() {
			this.stopped = true;
		}

		synchronized void send(String key, Object value) {
			if (!this.stopped) {
				this.connection.send(reply(this.message, this.session.id, key, value));
			}
		}

		synchronized void send(Map<String, Object> payload) {
			if (!this.stopped) {
				Map<String, Object> reply = reply(this.message, this.session.id);
				reply.putAll(payload);
				this.connection.send(reply);
			}
		}

		void run() {
			ClojureNrepl.Bindings working = this.session.bindings.copy();
			ReplyWriter out = new ReplyWriter(this, "out");
			ReplyWriter err = new ReplyWriter(this, "err");
			ClojureNrepl.Reply reply = new ClojureNrepl.Reply() {
				@Override
				public void value(String printed, @Nullable String namespace) {
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("value", printed);
					if (namespace != null) {
						payload.put("ns", namespace);
					}
					send(payload);
				}

				@Override
				public void error(String exClass, String rootExClass) {
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("status", List.of("eval-error"));
					payload.put("ex", exClass);
					payload.put("root-ex", rootExClass);
					send(payload);
				}
			};
			try {
				if (text(this.message, "op") instanceof String op && op.equals("load-file")) {
					String path = text(this.message, "file-path");
					String name = text(this.message, "file-name");
					NreplServer.this.clojure.loadFile(working, String.valueOf(text(this.message, "file")),
							path != null ? path : name, name, out, err, reply);
				}
				else {
					String ns = text(this.message, "ns");
					if (ns != null && !NreplServer.this.clojure.knowsNamespace(ns)) {
						Map<String, Object> payload = new LinkedHashMap<>();
						payload.put("ns", ns);
						payload.put("status", List.of("error", "namespace-not-found", "done"));
						send(payload);
						return;
					}
					NreplServer.this.clojure.eval(working, String.valueOf(text(this.message, "code")), ns,
							text(this.message, "file"), out, err, reply);
				}
				synchronized (this) {
					if (!this.stopped) {
						this.session.bindings = working;
					}
				}
				send("status", List.of("done"));
			}
			catch (LispInterruptSignal ex) {
				// answered interrupted when the interrupt came
			}
			catch (LispExitSignal ex) {
				// the oracle's System/exit ends its server: so does this one
				send("status", List.of("done"));
				NreplServer.this.exit = ex;
				close();
			}
			catch (RuntimeException ex) {
				send("err", String.valueOf(ex.getMessage()) + "\n");
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("status", List.of("eval-error"));
				payload.put("ex", "class " + ex.getClass().getName());
				payload.put("root-ex", "class " + ex.getClass().getName());
				send(payload);
				send("status", List.of("done"));
			}
		}

	}

	/**
	 * A message's {@code *out*} or {@code *err*}: what the evaluation writes goes to the
	 * client as the message's {@code out} or {@code err} slot, a line, a flush or a
	 * kilobyte at a time -- the oracle's writer flushes on {@code println} too.
	 */
	private static final class ReplyWriter extends Writer {

		private static final int LIMIT = 1024;

		private final Task task;

		private final String key;

		private final StringBuilder buffer = new StringBuilder();

		ReplyWriter(Task task, String key) {
			this.task = task;
			this.key = key;
		}

		@Override
		public synchronized void write(char[] chars, int offset, int length) {
			this.buffer.append(chars, offset, length);
			if (this.buffer.length() >= LIMIT || this.buffer.indexOf("\n") >= 0) {
				flush();
			}
		}

		@Override
		public synchronized void flush() {
			if (!this.buffer.isEmpty()) {
				String text = this.buffer.toString();
				this.buffer.setLength(0);
				this.task.send(this.key, text);
			}
		}

		@Override
		public void close() {
			flush();
		}

	}

}
