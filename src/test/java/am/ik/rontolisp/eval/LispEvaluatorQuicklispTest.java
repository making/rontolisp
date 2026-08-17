package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interpreter side of {@code ql:quickload} and {@code ql-dist:install-dist}: an
 * injected {@link DistClient} (in-memory dists, temporary cache) downloads a system,
 * whose {@code .asd}/source files are then loaded through the same
 * {@code asdf:load-system} machinery and become callable.
 */
class LispEvaluatorQuicklispTest {

	private static final String MYLIB_URL = "http://fake.quicklisp/archive/mylib-1.0.tar.gz";

	private static final String FRESH_URL = "http://fake.ultralisp/archive/fresh-1.0.tar.gz";

	private String run(String source, DistClient client) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setDistClient(client);
		for (LispVal expr : LispReader.readAllFromString(source)) {
			evaluator.eval(expr);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void quickloadDownloadsAndLoadsASystem(@TempDir Path base) {
		DistClient client = new DistClient(base, DistTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_URL, DistTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () (* 6 7))")))));

		String output = run("(ql:quickload \"mylib\") (print (mylib-answer))", client);

		assertThat(output).contains("42");
	}

	@Test
	void quickloadReturnsTheListOfLoadedSystemNames(@TempDir Path base) {
		DistClient client = new DistClient(base, DistTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_URL, DistTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)")))));

		String output = run("(print (ql:quickload \"mylib\"))", client);

		assertThat(output).contains("mylib");
	}

	@Test
	void installDistAddsADistQuickloadThenDownloadsFrom(@TempDir Path base) {
		// The system exists only in the second dist, so the quickload works only
		// because the program installed it first -- the opt-in shape upstream's
		// (ql-dist:install-dist "http://dist.ultralisp.org/" :prompt nil) has.
		DistClient client = new DistClient(base, DistTestSupport.dists(//
				DistTestSupport.quicklisp("", "", Map.of()), //
				DistTestSupport.ultralisp(//
						"fresh fresh fresh\n", //
						"fresh " + FRESH_URL + " 100 md5 sha1 fresh-1.0 fresh\n", //
						Map.of(FRESH_URL, DistTestSupport.tarGz(Map.of(//
								"fresh-1.0/fresh.asd", "(defsystem \"fresh\" :components ((:file \"fresh\")))", //
								"fresh-1.0/fresh.lisp", "(defun fresh-answer () (* 6 7))"))))));

		String output = run("""
				(print (ql-dist:install-dist "http://dist.ultralisp.org/" :prompt nil))
				(ql:quickload "fresh")
				(print (fresh-answer))
				""", client);

		assertThat(output).contains("ultralisp").contains("42");
	}

	@Test
	void quickloadWithoutTheDistInstalledReportsTheDistsItSearched(@TempDir Path base) {
		DistClient client = new DistClient(base,
				DistTestSupport.dists(DistTestSupport.quicklisp("", "", Map.of()), DistTestSupport.ultralisp(
						"fresh fresh fresh\n", "fresh " + FRESH_URL + " 100 md5 sha1 fresh-1.0 fresh\n", Map.of())));

		assertThatThrownBy(() -> run("(ql:quickload \"fresh\")", client)).isInstanceOf(LispEvalException.class)
			.hasMessageContaining("fresh")
			.hasMessageContaining("installed dists (quicklisp)");
	}

}
