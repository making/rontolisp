package am.ik.rontolisp.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CliOptions} argument parsing. The value-less flags (e.g. {@code --simd},
 * {@code --optimize}) must be recognized even as the trailing argument: a flag that is
 * not registered as value-less is parsed as expecting a value and, when it is the last
 * argument, is dropped entirely -- silently disabling the feature (the {@code --simd}
 * bug).
 */
class CliOptionsTest {

	@Test
	void trailingValueLessFlagsAreRecognized() {
		// The reproduction of the --simd bug: --simd trailing after `-o Out.class` must
		// be
		// contains-true. Before --simd was registered as value-less it consumed no value
		// and was dropped, so `contains("--simd")` returned false and the JVM
		// acceleration
		// was silently never enabled.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "-o", "Out.class", "--simd" });
		assertThat(options.contains("--simd")).isTrue();
		assertThat(options.getNokey()).isEqualTo("prog.lisp");
		assertThat(options.get("-o")).isEqualTo("Out.class");
	}

	@Test
	void everyValueLessFlagIsRecognizedTrailing() {
		for (String flag : new String[] { "--simd", "--blas", "--gpu", "--optimize", "--no-gc", "--component",
				"--no-wasi", "--dynamic", "--buffered-output", "--emit-wit" }) {
			CliOptions options = CliOptions.build(new String[] { "prog.lisp", "-o", "Out.class", flag });
			assertThat(options.contains(flag)).as(flag).isTrue();
			assertThat(options.get("-o")).as(flag).isEqualTo("Out.class");
		}
	}

	@Test
	void aValueLessFlagDoesNotSwallowTheFollowingArgument() {
		// --simd must not consume the next token as its value.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--simd", "-o", "Out.class" });
		assertThat(options.contains("--simd")).isTrue();
		assertThat(options.get("-o")).isEqualTo("Out.class");
	}

	@Test
	void aGluedValueIsReadWithoutMakingTheFlagConsumeAnArgument() {
		// --optimize=size gives a value-less flag a value without moving it out of
		// noValueKeys -- which it cannot leave: the space form would then read the
		// following -o as the level.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--optimize=size", "-o", "out.wasm" });
		assertThat(options.contains("--optimize")).isTrue();
		assertThat(options.get("--optimize")).isEqualTo("size");
		assertThat(options.get("-o")).isEqualTo("out.wasm");
		assertThat(options.getNokey()).isEqualTo("prog.lisp");
	}

	@Test
	void theBareFlagKeepsItsEmptyValue() {
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--optimize", "-o", "out.wasm" });
		assertThat(options.contains("--optimize")).isTrue();
		assertThat(options.get("--optimize")).isEmpty();
		assertThat(options.get("-o")).isEqualTo("out.wasm");
	}

	@Test
	void onlyTheFirstEqualsSignSplitsAGluedValue() {
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--world=ns:pkg/world=v1" });
		assertThat(options.get("--world")).isEqualTo("ns:pkg/world=v1");
	}

	@Test
	void theSpaceFormOfAValuedOptionIsRejectedRatherThanSwallowingTheInputFile() {
		// --optimize stays a no-value key, so its "value" would land as a second
		// positional argument and used to REPLACE prog.lisp: the compiler then looked
		// for a file named "size". Nothing takes a second positional, so say so.
		assertThatThrownBy(() -> CliOptions.build(new String[] { "prog.lisp", "--optimize", "size", "-o", "out.wasm" }))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("unexpected extra argument 'size'")
			.hasMessageContaining("--optimize=size");
	}

	@Test
	void inlineProgramsAccumulateInOrder() {
		// -e is repeatable: the occurrences form ONE program, joined the way the forms
		// would be written on separate lines of a file.
		CliOptions options = CliOptions.build(new String[] { "-e", "(defun f () 1)", "-e", "(print (f))" });
		assertThat(options.get("-e")).isEqualTo("(defun f () 1)\n(print (f))");
		assertThat(options.containsNoKey()).isFalse();
	}

	@Test
	void theLongSpellingIsTheSameKey() {
		// --eval is stored as -e, and the two spellings mixed in one command line keep
		// the order they were written in.
		assertThat(CliOptions.build(new String[] { "--eval", "(print 42)" }).get("-e")).isEqualTo("(print 42)");
		assertThat(CliOptions.build(new String[] { "--eval=(print 42)" }).get("-e")).isEqualTo("(print 42)");
		CliOptions options = CliOptions.build(new String[] { "-e", "(defun f () 1)", "--eval", "(print (f))" });
		assertThat(options.get("-e")).isEqualTo("(defun f () 1)\n(print (f))");
	}

	@Test
	void anInlineProgramIsNotParsedAsOptions() {
		// The value of -e is taken verbatim, so a program that starts with '-' or
		// contains '=' survives.
		assertThat(CliOptions.build(new String[] { "-e", "(- 1 2)" }).get("-e")).isEqualTo("(- 1 2)");
		assertThat(CliOptions.build(new String[] { "-e", "(print (= 1 1))" }).get("-e")).isEqualTo("(print (= 1 1))");
	}

	@Test
	void aTrailingValuedOptionIsRejected() {
		// It used to be dropped, silently changing the mode: a trailing -e opened the
		// REPL and a trailing -o interpreted instead of compiling.
		assertThatThrownBy(() -> CliOptions.build(new String[] { "-e" })).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("option '-e' requires a value");
		// The message names the spelling that was typed, not the canonical key.
		assertThatThrownBy(() -> CliOptions.build(new String[] { "--eval" }))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("option '--eval' requires a value");
		assertThatThrownBy(() -> CliOptions.build(new String[] { "prog.lisp", "-o" }))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("option '-o' requires a value");
	}

	@Test
	void aValueGivenAsTheFollowingArgumentKeepsItsEqualsSigns() {
		// The glued form must not reach into a value the space form supplies.
		CliOptions options = CliOptions.build(new String[] { "prog.lisp", "--system-path", "/a=b:/c" });
		assertThat(options.get("--system-path")).isEqualTo("/a=b:/c");
		assertThat(options.getNokey()).isEqualTo("prog.lisp");
	}

}
