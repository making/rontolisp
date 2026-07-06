package am.ik.rontolisp.e2e;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second real-library integration target (after split-sequence): parse-number v1.8
 * (BSD 3-Clause, vendored unmodified under {@code src/test/resources/parse-number}) loads
 * via {@code asdf:load-system} and parses integers, ratios, floats, radix-prefixed
 * literals and exponent markers. It exercises the residue fixed for it: symbol
 * single-escapes ({@code \\(-pos}), init-less {@code let} bindings, the
 * {@code (error 'type args...)} idiom, {@code values-list}, {@code /=}, the
 * {@code parse-integer} expansion (full keywords + second value), runtime-type
 * {@code coerce} and {@code *read-default-float-format*}. Runs on all four backends via
 * {@link AsdfLibraryE2eSupport}.
 */
class ParseNumberE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "parse-number")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :parse-number)
			(print (parse-number:parse-number "42"))
			(print (parse-number:parse-number "-13"))
			(print (parse-number:parse-number "  3.14  "))
			(print (parse-number:parse-number "1/3"))
			(print (parse-number:parse-number "-4/8"))
			(print (parse-number:parse-number "1e3"))
			(print (parse-number:parse-number "2.5e2"))
			(print (parse-number:parse-number "#xFF"))
			(print (parse-number:parse-number "#b101"))
			(print (parse-number:parse-number "#o777"))
			(print (parse-number:parse-number "#3r12"))
			(print (parse-number:parse-number "5d0"))
			(print (parse-number:parse-real-number "-42.5"))
			(print (parse-number:parse-positive-real-number "17"))
			""";

	private static final List<String> EXPECTED = List.of("42", "-13", "3.14", "1/3", "-1/2", "1000.0", "250.0", "255",
			"5", "511", "5", "5.0", "-42.5", "17");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "TestParseNumber";
	}

	@Test
	void parseNumberSignalsTheInvalidNumberIdiom() {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		evaluator.setSystemPath(List.of(SYSTEM_DIR));
		for (LispVal expr : LispReader.readAllFromString("(asdf:load-system :parse-number)")) {
			evaluator.eval(expr);
		}
		// The (error 'invalid-number :value ... :reason ...) idiom signals with the
		// type and the evaluated initargs in the message (lite condition system).
		assertThatThrownBy(() -> {
			for (LispVal expr : LispReader.readAllFromString("(parse-number:parse-number \"1.2.3\")")) {
				evaluator.eval(expr);
			}
		}).hasMessageContaining("invalid-number").hasMessageContaining("Multiple .'s in number");
	}

}
