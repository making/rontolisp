package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StdinLibraryTest {

	private static final WitExportDirective.Backend COMPONENT = WitExportDirective.Backend.WASM_COMPONENT;

	@Test
	void nonComponentBackendIsUntouched() {
		List<LispVal> program = LispReader
			.readAllFromString("(rontolisp:async-defun main () (print (read-line)))\n(rontolisp:await (main))");
		assertThat(StdinLibrary.process(program, WitExportDirective.Backend.WASM_GC, false)).isSameAs(program);
	}

	@Test
	void nonAsyncStdinProgramIsUntouched() {
		// The byte-stability contract: a synchronous stdin program gains nothing from
		// the migration and keeps the preview1 adapter's stdin branch (and its
		// wasmtime flags) untouched.
		List<LispVal> program = LispReader.readAllFromString("(print (read-line))");
		assertThat(StdinLibrary.process(program, COMPONENT, false)).isSameAs(program);
	}

	@Test
	void asyncProgramWithoutStdinReadIsUntouched() {
		List<LispVal> program = LispReader
			.readAllFromString("(rontolisp:async-defun main () 1)\n(print (rontolisp:await (main)))");
		assertThat(StdinLibrary.process(program, COMPONENT, false)).isSameAs(program);
	}

	@Test
	void asyncStdinProgramGetsTheDispatchSplice() {
		List<LispVal> program = LispReader
			.readAllFromString("(rontolisp:async-defun main () (print (read-line)))\n(rontolisp:await (main))");
		List<LispVal> out = StdinLibrary.process(program, COMPONENT, false);
		assertThat(definesInternal(out, "rontolisp::%io-read-line")).isTrue();
		assertThat(definesInternal(out, "rontolisp::%stdin-read-line-or-raw-f")).isTrue();
		assertThat(definesInternal(out, "rontolisp::%stdin-read-line-f")).isTrue();
	}

	@Test
	void nicknameSpellingsTrigger() {
		// rl: is a built-in nickname of rontolisp; cl:read-line is the qualified
		// spelling of the bare built-in. The scan runs before PackageResolver, so it
		// must normalize both itself.
		List<LispVal> program = LispReader
			.readAllFromString("(rl:async-defun main () (print (cl:read-line)))\n(rl:await (main))");
		List<LispVal> out = StdinLibrary.process(program, COMPONENT, false);
		assertThat(definesInternal(out, "rontolisp::%io-read-line")).isTrue();
	}

	@Test
	void serveStdinOnlyProgramIsUntouched() {
		// The wasi:http service world has no stdin; a served handler's fd_read is EOF
		// by construction, so there is nothing to migrate.
		List<LispVal> program = LispReader.readAllFromString(
				"(rontolisp:async-defun h (r) (list :status 200 :body (read-line)))\n(rontolisp:http-handler 'h)");
		assertThat(StdinLibrary.process(program, COMPONENT, true)).isSameAs(program);
	}

	@Test
	void socketsSplicedProgramGetsTheHelpersWithoutTheDispatchers() {
		// sockets.lisp's own dispatchers fall through to the %stdin-*-or-raw-f
		// helpers; the stdin-dispatch.lisp dispatchers must NOT be spliced beside
		// them (they would define %io-read-line twice).
		List<LispVal> program = SocketsLibrary
			.process(LispReader.readAllFromString("(close (rontolisp:tcp-listen 7777))"), COMPONENT);
		List<LispVal> out = StdinLibrary.process(program, COMPONENT, false);
		assertThat(definesInternal(out, "rontolisp::%stdin-read-line-or-raw-f")).isTrue();
		assertThat(definesInternal(out, "rontolisp::%stdin-read-line-f")).isTrue();
		assertThat(countDefuns(out, "rontolisp::%io-read-line")).isEqualTo(1);
	}

	@Test
	void socketsSplicedServeProgramGetsTheStub() {
		// A serve+tcp program still needs the or-raw helper names sockets.lisp's
		// dispatchers reference, but as raw passthroughs: the service world has no
		// wasi:cli/stdin to bind, so the real machinery (and its wit-import) must be
		// absent.
		List<LispVal> program = SocketsLibrary.process(LispReader.readAllFromString("""
				(defun h (r) (list :status 200 :body (rontolisp:tcp-peer-address 0)))
				(rontolisp:http-handler 'h)
				"""), COMPONENT);
		List<LispVal> out = StdinLibrary.process(program, COMPONENT, true);
		assertThat(definesInternal(out, "rontolisp::%stdin-read-line-or-raw-f")).isTrue();
		assertThat(definesInternal(out, "rontolisp::%stdin-read-line-f")).isFalse();
	}

	@Test
	void processingTwiceIsANoOp() {
		List<LispVal> program = LispReader
			.readAllFromString("(rontolisp:async-defun main () (print (read-line)))\n(rontolisp:await (main))");
		List<LispVal> once = StdinLibrary.process(program, COMPONENT, false);
		assertThat(StdinLibrary.process(once, COMPONENT, false)).isSameAs(once);
	}

	private static boolean definesInternal(List<LispVal> forms, String name) {
		return countDefuns(forms, name) > 0;
	}

	private static int countDefuns(List<LispVal> forms, String name) {
		int count = 0;
		for (LispVal form : forms) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& ("defun".equals(head.name()) || "rontolisp:async-defun".equals(head.name()))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol defunName
					&& name.equals(defunName.name())) {
				count++;
			}
		}
		return count;
	}

}
