package am.ik.rontolisp.clojure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.MissingFormatArgumentException;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.LispEvaluator;
import am.ik.rontolisp.eval.SourceLanguage;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.testsupport.CliStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code format}'s two parses of a specifier -- {@link ClojureFormatLowering#parse} for a
 * literal format string, {@code %clojure-format-parse} when the call runs -- against the
 * JDK's {@code Formatter}, the oracle of what each conversion refuses first and in which
 * words.
 */
class ClojureFormatLoweringTest {

	private static final String FLAGS = "-#+ 0,(<";

	// What the JDK's parse says of the format string: its refusal's class and words, or
	// nothing when the specifier parses (it then misses its argument, or prints).
	private static String jdk(String format) {
		try (Formatter formatter = new Formatter()) {
			formatter.format(format);
			return "";
		}
		catch (MissingFormatArgumentException ex) {
			return "";
		}
		catch (RuntimeException ex) {
			return ex.getClass().getName() + ":" + ex.getMessage();
		}
	}

	private static String lowering(String format) {
		try {
			ClojureFormatLowering.parse(format);
			return "";
		}
		catch (ClojureFormatLowering.Refused refused) {
			// no carrier: a conversion this front end does not render, parsed all the
			// same
			return (refused.carrier == null) ? "" : ClojureRefusals.CLASSES.get(refused.carrier) + ":" + refused.words;
		}
	}

	// Every conversion under every set of flags, with and without a width, a precision
	// and an explicit index, the flags written in their order and against it.
	private static List<String> formats(boolean everyVariant) {
		List<String> formats = new ArrayList<>();
		for (char conversion : "bBhHsScCdoxXeEfgGaA%n".toCharArray()) {
			for (int set = 0; set < (1 << FLAGS.length()); set++) {
				StringBuilder flags = new StringBuilder();
				for (int i = 0; i < FLAGS.length(); i++) {
					if ((set >> i & 1) != 0) {
						flags.append(FLAGS.charAt(i));
					}
				}
				for (String index : everyVariant ? new String[] { "", "1$" } : new String[] { "" }) {
					for (String width : new String[] { "", "5" }) {
						for (String precision : new String[] { "", ".2" }) {
							formats.add("%" + index + flags + width + precision + conversion);
							if (everyVariant) {
								formats.add("%" + index + new StringBuilder(flags).reverse() + width + precision
										+ conversion);
							}
						}
					}
				}
			}
		}
		return formats;
	}

	@Test
	void bothParsesRefuseASpecifierAsTheJdkDoes() throws Exception {
		List<String> differing = new ArrayList<>();
		for (String format : formats(true)) {
			String expected = jdk(format);
			String actual = lowering(format);
			if (!expected.equals(actual) && differing.size() < 10) {
				differing.add(format + ": " + actual + ", the JDK " + expected);
			}
		}
		assertThat(differing).as("the literal parse").isEmpty();

		// The run-time parse, reached by a format string that is no literal: one line per
		// format, the refusal's class and words or nothing.
		List<String> formats = formats(false);
		StringBuilder program = new StringBuilder("(doseq [f [");
		for (String format : formats) {
			program.append('"').append(format).append("\" ");
		}
		program.append("""
				]]
				  (println (try (format f) ""
				                (catch java.util.MissingFormatArgumentException e "")
				                (catch java.util.IllegalFormatException e (str (.getName (class e)) ":" (ex-message e)))
				                (catch Throwable e ""))))
				""");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CliStack.call("clojure-format", () -> {
			LispEvaluator evaluator = new LispEvaluator(new PrintStream(out, true, StandardCharsets.UTF_8));
			for (LispVal form : evaluator
				.clojureProgram(SourceLanguage.CLOJURE.read(program.toString(), Features.INTERPRETER, "probe.clj"))) {
				evaluator.eval(form);
			}
			return "";
		});
		List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
		assertThat(lines).hasSize(formats.size());
		for (int i = 0; i < formats.size(); i++) {
			String expected = jdk(formats.get(i));
			// a format that parses prints its text (%% and %n) or misses its argument
			String actual = lines.get(i).contains("Exception:") ? lines.get(i) : "";
			if (!expected.equals(actual) && differing.size() < 10) {
				differing.add(formats.get(i) + ": " + actual + ", the JDK " + expected);
			}
		}
		assertThat(differing).as("the run-time parse").isEmpty();
	}

}
