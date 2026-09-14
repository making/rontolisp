package am.ik.rontolisp.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.codegen.wasm.WasmLispCompiler;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A compile is a pure function of its input: what one program compiles to may not depend
 * on what else the same JVM has compiled, interpreted, or is compiling alongside it.
 *
 * <p>
 * <b>Why this is a test and not an obvious truth.</b> Every parsed library the splice
 * chain injects is cached for the life of the JVM -- the prelude
 * ({@code LispPreludeLibrary}), the ASDF shims ({@code ShimLibraries}), uiop, the
 * generated cl-unicode tables, and the one-field caches in a dozen {@code eval/*Library}
 * classes. A single pass that APPENDED to a list it was handed rather than to a copy of
 * it would grow every later compile in that JVM, silently and without bound. That is not
 * a hypothetical shape: one full {@code ./mvnw test} lost two workers to a WASM
 * dispatcher whose largest funcId was over {@code 2^24}, ~5600x the widest value any
 * shipped program produces, and a compile of the same program alone was green
 * ({@code .kb/wasm-function-body-size.md}). The caches now hand back immutable lists,
 * which makes the append impossible rather than merely absent; this test pins the
 * OBSERVABLE half, which no such change can cover on its own -- the output.
 *
 * <p>
 * The three conditions are the three the failing run had and a single compile does not:
 * the same program expanded again after other programs have warmed every cache, several
 * DIFFERENT programs compiling at once (the library E2Es run 16-way concurrent in two
 * forks), and the interpreter running the same sources in the same JVM as the compiles.
 * Repeating ONE program, however many times and however concurrently, cannot see any of
 * them.
 */
class CompileIndependenceTest {

	/**
	 * An exercise and the ASDF search path it resolves against. Deliberately cheap
	 * programs that between them reach every shared parsed-library cache: the built-in
	 * shim systems and usocket, uiop and the prelude, a user-macro-heavy tree, and one
	 * vendored system whose own sources dominate the splice.
	 */
	private record Case(String name, String exercise, List<String> systemPath) {
	}

	private static String dir(String name) {
		return Path.of("src", "test", "resources", name).toAbsolutePath().toString();
	}

	private static List<Case> cases() {
		return List.of(new Case("shims", """
				(asdf:load-system :usocket)
				(asdf:load-system :flexi-streams)
				(asdf:load-system :babel)
				(asdf:load-system :closer-mop)
				(format t "~A~%" (list 1 2 3))
				""", List.of()), new Case("uiop-prelude", """
				(format t "~A~%" (uiop:strcat "a" "b"))
				(format t "~A~%" (namestring (uiop:parse-unix-namestring "/tmp/x")))
				(format t "~A~%" (sort (list 3 1 2) #'<))
				""", List.of()), new Case("cl-ppcre", """
				(asdf:load-system :cl-ppcre)
				(format t "~A~%" (cl-ppcre:scan-to-strings "a(b)c" "xabcy"))
				""", List.of(dir("cl-ppcre"))), new Case("iterate", """
				(asdf:load-system :iterate)
				(format t "~A~%" (iterate:iter (iterate:for i from 1 to 3) (iterate:collect i)))
				""", List.of(dir("iterate"))));
	}

	@Test
	void aProgramCompilesToTheSameBytesWhateverElseTheJvmHasDone() throws Exception {
		List<Case> cases = cases();
		// The baseline: each program compiled on its own, in a JVM that has compiled
		// nothing else since the previous case.
		Map<String, String> baseline = new LinkedHashMap<>();
		for (Case c : cases) {
			for (boolean component : new boolean[] { false, true }) {
				baseline.put(key(c, component), compile(c, component));
			}
		}
		List<String> mismatches = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			// Different programs compiling at once, with the interpreter loading the
			// same sources beside them.
			List<Future<?>> futures = new ArrayList<>();
			for (Case c : cases) {
				futures.add(pool.submit(() -> interpret(c)));
				for (boolean component : new boolean[] { false, true }) {
					futures.add(pool.submit(() -> check(c, component, baseline, mismatches, "concurrent")));
				}
			}
			for (Future<?> f : futures) {
				f.get();
			}
		}
		finally {
			pool.shutdownNow();
		}
		// And once more sequentially, now that every cache in the JVM is warm and every
		// program above has been through the front end, the backend and the interpreter.
		for (Case c : cases) {
			for (boolean component : new boolean[] { false, true }) {
				check(c, component, baseline, mismatches, "after-everything");
			}
		}
		assertThat(mismatches).isEmpty();
	}

	private static void check(Case c, boolean component, Map<String, String> baseline, List<String> mismatches,
			String phase) {
		String digest = compile(c, component);
		if (!digest.equals(baseline.get(key(c, component)))) {
			mismatches.add(phase + " " + key(c, component) + ": expected " + baseline.get(key(c, component))
					+ " but was " + digest);
		}
	}

	private static String key(Case c, boolean component) {
		return c.name() + "#" + (component ? "component" : "preview1");
	}

	private static String compile(Case c, boolean component) {
		CompileFrontendAccess.Program program = CompileFrontendAccess.withSystemPath(c.exercise(), c.systemPath(), true,
				component);
		byte[] bytes = WasmLispCompiler.builder()
			.component(component)
			.runtimeFeatures(program.features().names())
			.build()
			.compile(program.forms());
		try {
			return bytes.length + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Loads the same sources through the interpreter, which caches the same parses. */
	private static void interpret(Case c) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setSystemPath(c.systemPath());
		for (LispVal expr : LispReader.readAllFromString(c.exercise())) {
			evaluator.eval(expr);
		}
	}

}
