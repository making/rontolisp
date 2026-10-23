package am.ik.rontolisp.eval;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.cli.CompileFrontendAccess;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.scheme.Scheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeLibraryTest {

	@ParameterizedTest
	@ValueSource(strings = { "(write '|a b|)", "(write '(x #(|1|)))", "(write (string->symbol \"a\"))",
			"(write (read))", "(define (f) '|#t|)", "(write '||)" })
	void aProgramThatCanHoldASymbolWrittenWithVerticalLinesGetsThePrinterArm(String source) {
		assertThat(SchemeLibrary.makesBarSymbols(Scheme.read(source, null), Features.INTERPRETER)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "(display \"hello\")", "(write '(a b ABC a:b ... +))", "(define |a b| 1) (write |a b|)",
			"(write (list #f (if #f #f) (interaction-environment)))", "(write (symbol->string 'abc))",
			"(write (list +inf.0 (floor 2.5)))" })
	void anyOtherProgramKeepsThePrinterItHad(String source) {
		assertThat(SchemeLibrary.makesBarSymbols(Scheme.read(source, null), Features.INTERPRETER)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "(eval '(cond-expand (r7rs 1)) (interaction-environment))",
			"(eval (read (open-input-string \"(cond-expand (else 2))\")) (interaction-environment))" })
	void aProgramThatSpellsCondExpandGetsTheEvalArm(String source) {
		assertThat(processed(source)).contains("(RONTOLISP::%SCHEME-EVAL-COND-EXPAND X)");
	}

	@ParameterizedTest
	@ValueSource(strings = { "(eval '(+ 1 2) (interaction-environment))",
			"(write (cond-expand (r7rs (eval 1 (interaction-environment)))))" })
	void anyOtherEvalHasNoCondExpandArm(String source) {
		assertThat(processed(source)).contains("%SCHEME-EVAL-KEYWORD-P")
			.doesNotContain("(RONTOLISP::%SCHEME-EVAL-COND-EXPAND X)");
	}

	private static String processed(String source) {
		return SchemeLibrary.process(Scheme.read(source, null), Features.INTERPRETER, SourceStandards.DEFAULT)
			.stream()
			.map(LispVal::print)
			.collect(Collectors.joining("\n"));
	}

	/**
	 * A Common Lisp program reads neither front end's run-time library: not when the
	 * interpreter resolves a prelude function or an undefined one for the first time, and
	 * not when the compile path splices and prunes the program. Observed in a class
	 * loader of its own, since this JVM's other tests fill the libraries' caches.
	 */
	@Test
	void aCommonLispProgramReadsNeitherFrontEndsLibrary(@TempDir Path dir) throws Exception {
		try (ResourceRecordingLoader loader = new ResourceRecordingLoader()) {
			Method readAll = loader.loadClass("am.ik.rontolisp.reader.LispReader")
				.getMethod("readAllFromString", String.class);
			Class<?> evaluatorClass = loader.loadClass(LispEvaluator.class.getName());
			Method eval = evaluatorClass.getMethod("eval", loader.loadClass(LispVal.class.getName()));
			Object evaluator = evaluatorClass.getConstructor(PrintStream.class)
				.newInstance(new PrintStream(new ByteArrayOutputStream()));
			Object last = null;
			for (Object form : (List<?>) readAll.invoke(null, """
					(equalp "a" "A")
					(string< "a" "b")
					(handler-case (no-such-function 1) (undefined-function () :undefined))
					""")) {
				last = eval.invoke(evaluator, form);
			}
			assertThat(String.valueOf(last)).contains("UNDEFINED");
			loader.loadClass(CompileFrontendAccess.class.getName())
				.getMethod("commonLisp", String.class, String.class, String.class, boolean.class, boolean.class)
				.invoke(null, "(defstruct point x) (print (equalp (make-point :x 1) (make-point :x 1)))",
						dir.resolve("p.lisp").toString(), dir.toString(), false, false);
			assertThat(loader.libraries()).isEmpty();
			// the observation sees a read: a Scheme helper's resolution reads its library
			for (Object form : (List<?>) readAll.invoke(null, "#'rontolisp::%scheme-acos")) {
				eval.invoke(evaluator, form);
			}
			assertThat(loader.libraries()).containsExactly("scheme.lisp");
		}
	}

	/**
	 * Loads rontolisp's classes afresh and records which front-end library sources they
	 * read.
	 */
	private static final class ResourceRecordingLoader extends URLClassLoader {

		private static final Set<String> LIBRARIES = Set.of("scheme.lisp", "clojure.lisp", "clojure-unicode.lisp");

		private final Set<String> read = ConcurrentHashMap.newKeySet();

		ResourceRecordingLoader() {
			super(new URL[] { location(LispEvaluator.class), location(CompileFrontendAccess.class) },
					ClassLoader.getPlatformClassLoader());
		}

		private static URL location(Class<?> type) {
			return type.getProtectionDomain().getCodeSource().getLocation();
		}

		@Override
		public URL findResource(String name) {
			String file = name.substring(name.lastIndexOf('/') + 1);
			if (LIBRARIES.contains(file)) {
				this.read.add(file);
			}
			return super.findResource(name);
		}

		Set<String> libraries() {
			return this.read;
		}

	}

}
