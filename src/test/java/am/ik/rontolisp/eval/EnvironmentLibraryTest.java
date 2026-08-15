package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentLibraryTest {

	// The CLI's order: the uiop splice first (it is what puts the %host-getenv reference
	// in the program -- the public uiop:getenv is a Lisp definition over the primitive),
	// then this pass, which binds the primitive to wasi:cli/environment on a component.
	private static List<LispVal> spliced(String source) {
		return EnvironmentLibrary.process(UiopLibrary.process(LispReader.readAllFromString(source)),
				WitExportDirective.Backend.WASM_COMPONENT);
	}

	@Test
	void processSplicesEnvironmentLibraryForAGetenvReference() {
		List<LispVal> out = spliced("(print (uiop:getenv \"DATABASE_URL\"))");
		assertThat(HttpLibraryTest.definesDefun(out, "%HOST-GETENV")).isTrue();
		// The lowered wit-import binds exactly the one member the defun calls.
		assertThat(out.stream().map(LispVal::print)).anyMatch(form -> form.contains("get-environment"));
	}

	@Test
	void processSplicesForTheDoubleColonSpelling() {
		// The uiop splice recognizes every source spelling of the member and answers the
		// HOME-package definition, whose body names the %host-getenv primitive this pass
		// keys on -- so the double-colon spelling reaches the binding too.
		assertThat(HttpLibraryTest.definesDefun(spliced("(print (uiop::getenv \"DATABASE_URL\"))"), "%HOST-GETENV"))
			.isTrue();
	}

	@Test
	void processIsANoOpWithoutAGetenvReference() {
		// A component that never reads the environment must declare no
		// wasi:cli/environment binding: that is what keeps its output byte-identical and
		// keeps it runnable on a host providing only the wasi:http service world.
		List<LispVal> program = LispReader.readAllFromString("(print (+ 1 2))");
		assertThat(EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT)).isEqualTo(program);
	}

	@Test
	void processIsANoOpOffTheComponentBackend() {
		// Preview 1 keeps the host-filled environ buffer scan (_getenv); the interpreter
		// and the JVM keep System.getenv.
		List<LispVal> program = UiopLibrary.process(LispReader.readAllFromString("(print (uiop:getenv \"X\"))"));
		assertThat(EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_GC)).isEqualTo(program);
		assertThat(EnvironmentLibrary.process(program, WitExportDirective.Backend.OTHER)).isEqualTo(program);
	}

	@Test
	void processDoesNotSpliceWhenTheProgramDefinesTheHostPrimitiveItself() {
		List<LispVal> program = LispReader
			.readAllFromString("(defun %host-getenv (name) name)\n(print (%host-getenv \"X\"))");
		assertThat(EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT)).isEqualTo(program);
	}

}
