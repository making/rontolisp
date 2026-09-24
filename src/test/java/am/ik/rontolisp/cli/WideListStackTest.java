package am.ik.rontolisp.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A program's stack ceiling is its NESTING, never the length of one of its lists.
 * <p>
 * Every leg runs the whole command line in process on a thread of {@link #STACK_BYTES} --
 * a sixteenth of what the CLI gives a program -- over lists of {@link #ELEMENTS}
 * elements: a walk that spends even the smallest Java frame per element needs several
 * times that stack, so any pass, splice detector or backend scan that recurses down a cdr
 * fails here, whichever backend it belongs to (.kb/test-execution.md, "In-process program
 * work runs on the CLI's stack").
 */
class WideListStackTest {

	private static final long STACK_BYTES = 1L << 20;

	private static final int ELEMENTS = 50_000;

	/**
	 * Levels of {@code (g ...)}: measured 2026-09-24 through the CLI, 2,000 overflow
	 * {@code --stack 1} and 4,000 compile at the default in about six seconds.
	 */
	private static final int NESTING = 4_000;

	@TempDir
	Path dir;

	static Stream<Arguments> shapes() {
		String progn = "(defvar *x* 0)\n(progn\n" + repeat(i -> " (setq *x* (+ *x* 1))", "\n") + ")\n(print *x*)\n";
		String numbers = "(defparameter *table* '(" + repeat(i -> Integer.toString(i % 1000), " ")
				+ "))\n(print (length *table*))\n";
		String symbols = "(defparameter *table* '(" + repeat(i -> "s" + (i % 1000), " ")
				+ "))\n(print (length *table*))\n";
		// The reader's backquote expansion, interpreted: compiled, the template becomes
		// one
		// call of ELEMENTS arguments, which a JVM method cannot hold.
		String backquote = "(defun g (x) `(,x " + repeat(i -> Integer.toString(i % 1000), " ")
				+ "))\n(print (length (g 1)))\n";
		// --no-gc compiles defuns only: one body of ELEMENTS forms.
		String body = "(defun f (x)\n" + repeat(i -> " (setq x (+ x 1))", "\n")
				+ " x)\n(rontolisp:wasm-export 'f :params '(:int) :returns :int)\n";
		List<Arguments> legs = new ArrayList<>();
		for (String target : List.of("interpret", "jvm", "wasm", "component")) {
			legs.add(Arguments.of("progn", target, progn, ELEMENTS));
			legs.add(Arguments.of("quoted-numbers", target, numbers, ELEMENTS));
			legs.add(Arguments.of("quoted-symbols", target, symbols, ELEMENTS));
		}
		legs.add(Arguments.of("backquote", "interpret", backquote, ELEMENTS + 1));
		legs.add(Arguments.of("defun-body", "no-gc", body, 0));
		return legs.stream();
	}

	private static String repeat(IntFunction<String> element, String separator) {
		return IntStream.range(0, ELEMENTS).mapToObj(element).collect(Collectors.joining(separator));
	}

	@ParameterizedTest(name = "{0} on {1}")
	@MethodSource("shapes")
	void aWideListCostsNoStackPerElement(String shape, String target, String source, int expected) throws Exception {
		Path file = this.dir.resolve(shape + ".lisp");
		Files.writeString(file, source);
		String out = runOnSmallStack(switch (target) {
			case "interpret" -> List.of(file.toString());
			case "jvm" ->
				List.of("-o", this.dir.resolve("Wide.class").toString(), "--class-name", "Wide", file.toString());
			case "wasm" -> List.of("-o", this.dir.resolve("wide.wasm").toString(), file.toString());
			case "no-gc" -> List.of("--no-gc", "-o", this.dir.resolve("wide.wasm").toString(), file.toString());
			case "component" -> List.of("--component", "-o", this.dir.resolve("wide.wasm").toString(), file.toString());
			default -> throw new IllegalArgumentException(target);
		});
		if ("interpret".equals(target)) {
			assertThat(out.strip()).isEqualTo(Integer.toString(expected));
		}
		else {
			Path output = this.dir.resolve("jvm".equals(target) ? "Wide.class" : "wide.wasm");
			assertThat(Files.size(output)).isPositive();
		}
	}

	@Test
	void aListClosedIntoItsOwnTailIsRefusedAtTheRead() throws Exception {
		Path file = this.dir.resolve("circular.lisp");
		Files.writeString(file, "(defvar *c* '#1=(a b . #1#))\n(print (car *c*))\n");
		assertThatThrownBy(() -> runOnSmallStack(List.of(file.toString()))).isInstanceOf(LispReadException.class)
			.hasMessageContaining("circular list literal");
		assertThatThrownBy(() -> runOnSmallStack(
				List.of("-o", this.dir.resolve("C.class").toString(), "--class-name", "C", file.toString())))
			.isInstanceOf(LispReadException.class)
			.hasMessageContaining("circular list literal");
	}

	@Test
	void aLabelThatOnlySharesStructureStillReads() throws Exception {
		Path file = this.dir.resolve("shared.lisp");
		Files.writeString(file, "(defvar *s* '(#1=(a b) #1#))\n(print (eq (first *s*) (second *s*)))\n");
		assertThat(runOnSmallStack(List.of(file.toString())).strip()).isEqualTo("T");
	}

	/**
	 * An embedder calls {@link JvmSourceCompiler} on whatever thread it has -- Maven's
	 * main thread carries 1 MiB on linux-x64 -- and still gets the command line's
	 * ceiling: a nesting the CLI compiles only with more than {@link #STACK_BYTES}
	 * compiles from a caller of that size too.
	 */
	@Test
	void anEmbedderCompilesOnTheCliStackNotItsOwn() throws Exception {
		String nested = "(defun g (x) x)\n(defun f (x) " + "(g ".repeat(NESTING) + "x" + ")".repeat(NESTING)
				+ ")\n(print (f 0))\n";
		Path file = this.dir.resolve("nested.lisp");
		Files.writeString(file, nested);
		assertThatThrownBy(() -> runOnSmallStack(
				List.of("-o", this.dir.resolve("N.class").toString(), "--class-name", "N", file.toString())))
			.as("the CLI on a thread of STACK_BYTES")
			.isInstanceOf(StackOverflowError.class);
		JvmSourceCompiler.Result result = SizedThread.call("embedder", STACK_BYTES,
				() -> new JvmSourceCompiler("Nested").compile(nested, null));
		assertThat(result.classBytes()).isNotEmpty();
	}

	/** Runs the command line in process on a thread of {@link #STACK_BYTES}. */
	private static String runOnSmallStack(List<String> args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SizedThread.call("wide-list", STACK_BYTES, () -> {
			RontoLispCli cli = new RontoLispCli(new ByteArrayInputStream(new byte[0]),
					new PrintStream(out, true, StandardCharsets.UTF_8));
			cli.run(args.toArray(String[]::new));
			return null;
		});
		return out.toString(StandardCharsets.UTF_8);
	}

}
