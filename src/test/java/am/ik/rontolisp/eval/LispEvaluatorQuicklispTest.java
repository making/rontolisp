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

/**
 * The interpreter side of {@code ql:quickload}: an injected {@link QuicklispClient}
 * (in-memory dist, temporary cache) downloads a system, whose {@code .asd}/source files
 * are then loaded through the same {@code asdf:load-system} machinery and become
 * callable.
 */
class LispEvaluatorQuicklispTest {

	private static final String MYLIB_URL = "http://fake.quicklisp/archive/mylib-1.0.tar.gz";

	private String run(String source, QuicklispClient client) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
		evaluator.setQuicklispClient(client);
		for (LispVal expr : LispReader.readAllFromString(source)) {
			evaluator.eval(expr);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void quickloadDownloadsAndLoadsASystem(@TempDir Path home) {
		QuicklispClient client = new QuicklispClient(home, QuicklispTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_URL, QuicklispTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () (* 6 7))")))));

		String output = run("(ql:quickload \"mylib\") (print (mylib-answer))", client);

		assertThat(output).contains("42");
	}

	@Test
	void quickloadReturnsTheListOfLoadedSystemNames(@TempDir Path home) {
		QuicklispClient client = new QuicklispClient(home, QuicklispTestSupport.dist(//
				"mylib mylib mylib\n", //
				"mylib " + MYLIB_URL + " 100 md5 sha1 mylib-1.0 mylib\n", //
				Map.of(MYLIB_URL, QuicklispTestSupport.tarGz(Map.of(//
						"mylib-1.0/mylib.asd", "(defsystem \"mylib\" :components ((:file \"mylib\")))", //
						"mylib-1.0/mylib.lisp", "(defun mylib-answer () 42)")))));

		String output = run("(print (ql:quickload \"mylib\"))", client);

		assertThat(output).contains("mylib");
	}

}
