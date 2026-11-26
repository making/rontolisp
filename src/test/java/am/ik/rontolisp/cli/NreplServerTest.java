package am.ik.rontolisp.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.rontolisp.eval.ClojureNrepl;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceStandards;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nREPL server against a client speaking bencode over a socket, each reply checked
 * against the one the oracle's server (nREPL 1.3.1, clj 1.12.6) answers the same message
 * with, the session id and the message id left out. Where the two differ the reply is
 * rontolisp's: no {@code eval} class name in a report's place ({@code at user/eval2502
 * (REPL:1)} is {@code at (REPL:1)}) and no hash in a printed namespace.
 */
class NreplServerTest {

	@Test
	void aSessionEvaluatesSwitchesNamespacesAndLoadsAFileAnsweringWhatTheOracleAnswers() throws Exception {
		try (NreplServer server = start(); Client client = new Client(server.port())) {
			Map<String, Object> describe = client.call(Map.of("op", "describe")).getFirst();
			assertThat(((Map<?, ?>) Objects.requireNonNull(describe.get("ops"))).keySet()
				.stream()
				.map(String::valueOf)
				.toList()).containsExactlyInAnyOrder("clone", "close", "completions", "describe", "eval", "interrupt",
						"load-file", "ls-sessions");
			assertThat(String.valueOf(describe.get("versions"))).contains("version-string=1.12.6")
				.contains("version-string=1.3.1");
			assertThat(describe.get("aux")).isEqualTo(Map.of("current-ns", "user"));
			String session = client.newSession();
			// each form's value with the namespace then current, its output before it;
			// a failure reports on err and the forms after it run
			assertThat(client.call(eval(session, "(+ 1 2) (println \"hi\") (/ 1 0) :k"))).containsExactly(
					Map.of("ns", "user", "value", "3"), Map.of("out", "hi\n"), Map.of("ns", "user", "value", "nil"),
					Map.of("err", "Execution error (ArithmeticException) at (REPL:1).\nDivide by zero\n"),
					Map.of("ex", "class java.lang.ArithmeticException", "root-ex",
							"class java.lang.ArithmeticException", "status", List.of("eval-error")),
					Map.of("ns", "user", "value", ":k"), Map.of("status", List.of("done")));
			// a namespace switch carries to the session's next message, and a caller
			// reaches a function redefined after it, as the oracle's calls through the
			// var do
			assertThat(client.call(eval(session, "(ns t.a) (defn g [] 1) (defn f [] (g)) (f) (defn g [] 2) (f)")))
				.containsExactly(Map.of("ns", "t.a", "value", "nil"), Map.of("ns", "t.a", "value", "#'t.a/g"),
						Map.of("ns", "t.a", "value", "#'t.a/f"), Map.of("ns", "t.a", "value", "1"),
						Map.of("ns", "t.a", "value", "#'t.a/g"), Map.of("ns", "t.a", "value", "2"),
						Map.of("status", List.of("done")));
			Map<String, Object> inUser = eval(session, "(str *ns*)");
			inUser.put("ns", "user");
			assertThat(client.call(inUser)).containsExactly(Map.of("ns", "user", "value", "\"user\""),
					Map.of("status", List.of("done")));
			Map<String, Object> nowhere = eval(session, "1");
			nowhere.put("ns", "no.such");
			assertThat(client.call(nowhere))
				.containsExactly(Map.of("ns", "no.such", "status", List.of("error", "namespace-not-found", "done")));
			// a file's namespace is the load's own, and the load is one history value
			Map<String, Object> load = new LinkedHashMap<>(Map.of("op", "load-file", "session", session, "file",
					"(ns t.b)\n(defn h [] 42)\n(h)", "file-path", "t/b.clj", "file-name", "b.clj"));
			assertThat(client.call(load)).containsExactly(Map.of("value", "42"), Map.of("status", List.of("done")));
			assertThat(client.call(eval(session, "[(str *ns*) *1 (t.a/f)]")))
				.containsExactly(Map.of("ns", "t.a", "value", "[\"t.a\" 42 2]"), Map.of("status", List.of("done")));
			// another session calls what this one defined, from a namespace of its own
			String other = client.newSession();
			assertThat(client.call(eval(other, "[(str *ns*) (t.a/f)]")))
				.containsExactly(Map.of("ns", "user", "value", "[\"user\" 2]"), Map.of("status", List.of("done")));
			Map<String, Object> complete = new LinkedHashMap<>(
					Map.of("op", "completions", "session", session, "prefix", "m-i", "ns", "user"));
			assertThat(client.call(complete)).containsExactly(Map.of("completions",
					List.of(Map.of("candidate", "map-indexed", "ns", "clojure.core", "type", "function")), "status",
					List.of("done")));
			assertThat(client.call(Map.of("op", "interrupt", "session", session)))
				.containsExactly(Map.of("status", List.of("done", "session-idle")));
			assertThat(client.call(Map.of("op", "frobnicate", "session", session)))
				.containsExactly(Map.of("op", "frobnicate", "status", List.of("done", "unknown-op", "error")));
			assertThat(String.valueOf(client.call(Map.of("op", "ls-sessions")).getFirst().get("sessions")))
				.contains(session)
				.contains(other);
			assertThat(client.call(Map.of("op", "close", "session", other)))
				.containsExactly(Map.of("status", List.of("done", "session-closed")));
			assertThat(client.call(eval(other, "1")))
				.containsExactly(Map.of("status", List.of("done", "unknown-session", "error")));
		}
	}

	@Test
	void anInterruptStopsTheRunningEvaluationAndTheSessionGoesOn() throws Exception {
		try (NreplServer server = start(); Client client = new Client(server.port())) {
			String session = client.newSession();
			Map<String, Object> loop = eval(session, "(do (println \"started\") (loop [] (recur)))");
			client.send(loop);
			assertThat(client.next()).containsEntry("out", "started\n");
			// an interrupt naming another message changes nothing
			assertThat(client.call(Map.of("op", "interrupt", "session", session, "interrupt-id", "another")))
				.containsExactly(Map.of("status", List.of("error", "interrupt-id-mismatch", "done")));
			Map<String, Object> interrupt = new LinkedHashMap<>(
					Map.of("op", "interrupt", "session", session, "interrupt-id", loop.get("id")));
			client.send(interrupt);
			assertThat(List.of(client.next(), client.next())).containsExactly(
					Map.of("id", loop.get("id"), "session", session, "status", List.of("interrupted", "done")),
					Map.of("id", interrupt.get("id"), "session", session, "status", List.of("done")));
			// the interrupted message records nothing; the session evaluates on
			assertThat(client.call(eval(session, "[(+ 40 2) *1]")))
				.containsExactly(Map.of("ns", "user", "value", "[42 nil]"), Map.of("status", List.of("done")));
		}
	}

	private static NreplServer start() throws IOException {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()),
				InputStream.nullInputStream());
		NreplServer server = new NreplServer(new ClojureNrepl(evaluator, SourceStandards.DEFAULT, null), evaluator,
				InetAddress.getLoopbackAddress(), 0, SizedThread.WORKER_STACK_BYTES);
		Thread.ofVirtual().start(() -> {
			try {
				server.serve();
			}
			catch (IOException ex) {
				// the test closed it
			}
		});
		return server;
	}

	private static Map<String, Object> eval(String session, String code) {
		return new LinkedHashMap<>(Map.of("op", "eval", "session", session, "code", code));
	}

	/** A client sending one message at a time, each under an id of its own. */
	private static final class Client implements AutoCloseable {

		private static final AtomicInteger IDS = new AtomicInteger();

		private final Socket socket;

		private final InputStream in;

		private final OutputStream out;

		Client(int port) throws IOException {
			this.socket = new Socket(InetAddress.getLoopbackAddress(), port);
			// a server that answers nothing fails the test rather than hanging it
			this.socket.setSoTimeout(120_000);
			this.in = new BufferedInputStream(this.socket.getInputStream());
			this.out = new BufferedOutputStream(this.socket.getOutputStream());
		}

		void send(Map<String, Object> message) throws IOException {
			Map<String, Object> sent = new LinkedHashMap<>(message);
			sent.putIfAbsent("id", "m" + IDS.incrementAndGet());
			if (message instanceof LinkedHashMap<String, Object> mutable) {
				mutable.putIfAbsent("id", sent.get("id"));
			}
			Bencode.write(this.out, sent);
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> next() throws IOException {
			return (Map<String, Object>) Objects.requireNonNull(Bencode.read(this.in));
		}

		/** Clones a fresh session, answering its id. */
		String newSession() throws IOException {
			return String.valueOf(call(Map.of("op", "clone")).getFirst().get("new-session"));
		}

		/**
		 * Sends a message and answers its replies up to the one whose status holds
		 * {@code done}, without their message and session ids.
		 */
		List<Map<String, Object>> call(Map<String, Object> message) throws IOException {
			Map<String, Object> sent = new LinkedHashMap<>(message);
			send(sent);
			List<Map<String, Object>> replies = new ArrayList<>();
			while (true) {
				Map<String, Object> reply = new LinkedHashMap<>(next());
				reply.remove("id");
				reply.remove("session");
				replies.add(reply);
				if (reply.get("status") instanceof List<?> status && status.contains("done")) {
					return replies;
				}
			}
		}

		@Override
		public void close() throws IOException {
			this.socket.close();
		}

	}

}
