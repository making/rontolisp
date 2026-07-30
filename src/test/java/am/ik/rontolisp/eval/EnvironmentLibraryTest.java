package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentLibraryTest {

	@Test
	void processSplicesEnvironmentLibraryForAGetenvReference() {
		List<LispVal> program = LispReader.readAllFromString("(print (uiop:getenv \"DATABASE_URL\"))");
		List<LispVal> out = EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		assertThat(HttpLibraryTest.definesDefun(out, "UIOP:GETENV")).isTrue();
		// The lowered wit-import binds exactly the one member the defun calls.
		assertThat(out.stream().map(LispVal::print)).anyMatch(form -> form.contains("get-environment"));
	}

	@Test
	void processSplicesForTheDoubleColonSpelling() {
		// The splice scan runs before PackageResolver normalizes the program, so it must
		// normalize the spelling itself.
		List<LispVal> program = LispReader.readAllFromString("(print (uiop::getenv \"DATABASE_URL\"))");
		List<LispVal> out = EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT);
		assertThat(HttpLibraryTest.definesDefun(out, "UIOP:GETENV")).isTrue();
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
		List<LispVal> program = LispReader.readAllFromString("(print (uiop:getenv \"DATABASE_URL\"))");
		assertThat(EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_GC)).isEqualTo(program);
		assertThat(EnvironmentLibrary.process(program, WitExportDirective.Backend.OTHER)).isEqualTo(program);
	}

	@Test
	void processDoesNotSpliceWhenTheProgramDefinesGetenvItself() {
		List<LispVal> program = LispReader
			.readAllFromString("(defun uiop:getenv (name) name)\n(print (uiop:getenv \"X\"))");
		assertThat(EnvironmentLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT)).isEqualTo(program);
	}

}
