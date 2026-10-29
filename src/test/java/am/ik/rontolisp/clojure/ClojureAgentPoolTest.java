package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.cli.JvmSourceCompiler;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.eval.SourceStandards;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import am.ik.rontolisp.testsupport.ThreadStdio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The shared agent pool (g38): on the interpreter and the JVM a send answers at once and
 * only queues the action -- a gate only the main thread delivers proves the sender was
 * not blocked -- while one agent's actions still run one at a time in send order and
 * converge after await, failures still stop a {@code :fail} agent until
 * {@code restart-agent} (or go on under {@code :continue}) and the error handler still
 * runs, all oracle-identical (clj 1.12.6). The WASM legs keep the sender-thread run
 * (single-threaded modules): a send to an idle agent runs its action before it returns;
 * so does the interpreter where a spawn runs its body at once (the browser playground),
 * since a pool worker ends with the queue empty.
 */
class ClojureAgentPoolTest {

	@TempDir
	Path dir;

	/** A send answers at once while its action waits on a gate; await converges. */
	private static final String NON_BLOCKING = """
			(def g38-gate (promise))
			(def g38-a (agent 0))
			(println (= g38-a (send g38-a (fn [v] @g38-gate 1))))
			(println :sent)
			(deliver g38-gate nil)
			(await g38-a)
			(println @g38-a)
			""";

	/**
	 * One agent's actions run one at a time in send order, and sends from several future
	 * threads converge.
	 */
	private static final String ORDERED = """
			(def g38-b (agent []))
			(dotimes [i 5] (let [j i] (send g38-b (fn [v] (conj v j)))))
			(await g38-b)
			(println @g38-b)
			(def g38-c (agent 0))
			(def g38-futs (mapv (fn [_] (future (dotimes [_ 50] (send g38-c inc)))) (range 4)))
			(doseq [f g38-futs] @f)
			(await g38-c)
			(println @g38-c)
			""";

	/** The error modes over the pool: fail, restart, continue and the handler. */
	private static final String ERRORS = """
			(defn g38-wait [a] (loop [] (when-not (agent-error a) (try (await-for 10 a) (catch RuntimeException e nil)) (recur))))
			(def g38-fa (agent 1 :validator pos?))
			(send g38-fa dec)
			(g38-wait g38-fa)
			(println [@g38-fa (some? (agent-error g38-fa))])
			(println (try (send g38-fa inc) (catch RuntimeException e :refused)))
			(println [(restart-agent g38-fa 5) @g38-fa])
			(send g38-fa inc)
			(await g38-fa)
			(println @g38-fa)
			(def g38-fc (agent 0 :error-mode :continue :error-handler (fn [ag e] (send ag + 100))))
			(send g38-fc (fn [_] (throw (Exception. "boom"))))
			(send g38-fc inc)
			(await g38-fc)
			(println [@g38-fc (agent-error g38-fc)])
			""";

	/** The sender-thread run: the action has run before send returns. */
	private static final String SENDER_THREAD = """
			(def g38-w (agent 0))
			(send g38-w + 41)
			(println @g38-w)
			(await g38-w)
			(println @g38-w :ok)
			""";

	/**
	 * Common Lisp run before the program: the browser playground's spawn
	 * ({@code Target_AsyncRuntime.spawnThread}), whose body runs to completion before
	 * make-thread returns.
	 */
	private static final String SYNCHRONOUS_SPAWN = """
			(defun rontolisp:make-thread (fn &optional bindings) (funcall fn))
			""";

	@Test
	void slowActionsDoNotBlockTheSender() throws Exception {
		assertThat(interpret("", NON_BLOCKING)).isEqualTo("true\n:sent\n1\n");
		assertThat(runOnJvm(NON_BLOCKING)).isEqualTo("true\n:sent\n1\n");
	}

	@Test
	void actionsRunOneAtATimeInSendOrder() throws Exception {
		assertThat(interpret("", ORDERED)).isEqualTo("[0 1 2 3 4]\n200\n");
		assertThat(runOnJvm(ORDERED)).isEqualTo("[0 1 2 3 4]\n200\n");
	}

	@Test
	void errorModesMatchTheOracle() throws Exception {
		assertThat(interpret("", ERRORS)).isEqualTo("[1 true]\n:refused\n[5 5]\n6\n[101 nil]\n");
		assertThat(runOnJvm(ERRORS)).isEqualTo("[1 true]\n:refused\n[5 5]\n6\n[101 nil]\n");
	}

	@Test
	void synchronousSpawnRunsTheQueueBeforeSendReturns() throws Exception {
		assertThat(interpret(SYNCHRONOUS_SPAWN, SENDER_THREAD)).isEqualTo("41\n41 :ok\n");
	}

	@Test
	void senderThreadRunsTheQueueOnPreview1() throws Exception {
		assertThat(runOnWasm(SENDER_THREAD, false)).isEqualTo("41\n41 :ok\n");
	}

	@Test
	void senderThreadRunsTheQueueOnTheComponent() throws Exception {
		assertThat(runOnWasm(SENDER_THREAD, true)).isEqualTo("41\n41 :ok\n");
	}

	/**
	 * Runs the Common Lisp prelude, then the program, on the interpreter, failing instead
	 * of hanging past 3 minutes.
	 */
	private static String interpret(String prelude, String program) throws Exception {
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
		try {
			return pool.submit(() -> {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				CliStack.call("clojure-agent-pool", () -> {
					LispEvaluator evaluator = new LispEvaluator(
							new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
					for (LispVal form : SourceLanguage.COMMON_LISP.read(prelude, Features.INTERPRETER, "prelude.lisp",
							SourceStandards.DEFAULT, SourceLoader.fileSystem())) {
						evaluator.eval(form);
					}
					for (LispVal form : evaluator.clojureProgram(SourceLanguage.CLOJURE.read(program,
							Features.INTERPRETER, "test.clj", SourceStandards.DEFAULT, SourceLoader.fileSystem()))) {
						evaluator.eval(form);
					}
					return null;
				});
				return out.toString(StandardCharsets.UTF_8);
			}).get(180, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** Runs the program on the JVM, failing instead of hanging past 3 minutes. */
	private String runOnJvm(String program) throws Exception {
		Path work = Files.createDirectories(this.dir.resolve("jvm" + System.nanoTime()));
		String name = "ClojureAgentPool";
		JvmSourceCompiler.Result result = new JvmSourceCompiler(name).sourceLanguage("clojure").compile(program, null);
		Files.write(work.resolve(name + ".class"), result.classBytes());
		for (Map.Entry<String, byte[]> file : result.runtimeClasses().entrySet()) {
			Path target = work.resolve(file.getKey());
			Files.createDirectories(target.getParent());
			Files.write(target, file.getValue());
		}
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
		try {
			return pool.submit(() -> {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				try (var loader = new URLClassLoader(new URL[] { work.toUri().toURL() },
						ClassLoader.getSystemClassLoader()); var _ = ThreadStdio.out(out)) {
					CliStack.call("clojure-agent-pool", () -> {
						try {
							Method main = loader.loadClass(name).getMethod("main", String[].class);
							main.invoke(null, (Object) new String[0]);
						}
						catch (ReflectiveOperationException ex) {
							throw new IllegalStateException(ex);
						}
						return null;
					});
				}
				return out.toString(StandardCharsets.UTF_8);
			}).get(180, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Compiles and runs the program on a WASM leg, requiring it to exit normally, and
	 * answers its output.
	 */
	private String runOnWasm(String program, boolean component) throws Exception {
		CompileFrontendAccess.Program frontend = CompileFrontendAccess.clojure(program, true, component);
		ByteArrayOutputStream warnings = new ByteArrayOutputStream();
		byte[] module;
		try (var _ = ThreadStdio.err(warnings)) {
			module = WasmLispCompiler.builder()
				.component(component)
				.runtimeFeatures(frontend.features().names())
				.build()
				.compile(frontend.forms());
		}
		assertThat(warnings.toString(StandardCharsets.UTF_8)).as("no compile warnings").isEmpty();
		requireWasmtime();
		Path path = Files.createTempFile(this.dir, "agentpool", component ? "-c.wasm" : ".wasm");
		Files.write(path, module);
		Path outFile = Files.createTempFile(this.dir, "agentpool", ".out");
		Path errFile = Files.createTempFile(this.dir, "agentpool", ".err");
		Process process = new ProcessBuilder("wasmtime", "run", "-W", "gc=y", "-W", "exceptions=y", path.toString())
			.redirectOutput(outFile.toFile())
			.redirectError(errFile.toFile())
			.start();
		try {
			assertThat(process.waitFor(300, TimeUnit.SECONDS)).isTrue();
			assertThat(process.exitValue()).as("wasmtime exit code: %s", Files.readString(errFile)).isZero();
			return Files.readString(outFile);
		}
		finally {
			Files.deleteIfExists(outFile);
			Files.deleteIfExists(errFile);
		}
	}

	private static void requireWasmtime() {
		try {
			Process probe = new ProcessBuilder("wasmtime", "--version").start();
			if (!probe.waitFor(60, TimeUnit.SECONDS) || probe.exitValue() != 0) {
				abort("no usable wasmtime on PATH");
			}
		}
		catch (Exception ex) {
			abort("no usable wasmtime on PATH");
		}
	}

}
