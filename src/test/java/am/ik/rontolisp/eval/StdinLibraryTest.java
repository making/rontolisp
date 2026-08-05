package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Map;

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
		assertThat(definesInternal(out, "RONTOLISP::%IO-READ-LINE")).isTrue();
		assertThat(definesInternal(out, "RONTOLISP::%STDIN-READ-LINE-OR-RAW-F")).isTrue();
		assertThat(definesInternal(out, "RONTOLISP::%STDIN-READ-LINE-F")).isTrue();
	}

	@Test
	void nicknameSpellingsTrigger() {
		// rl: is a built-in nickname of rontolisp; cl:read-line is the qualified
		// spelling of the bare built-in. The scan runs before PackageResolver, so it
		// must normalize both itself.
		List<LispVal> program = LispReader
			.readAllFromString("(rl:async-defun main () (print (cl:read-line)))\n(rl:await (main))");
		List<LispVal> out = StdinLibrary.process(program, COMPONENT, false);
		assertThat(definesInternal(out, "RONTOLISP::%IO-READ-LINE")).isTrue();
	}

	@Test
	void serveStdinOnlyProgramIsUntouched() {
		// The wasi:http service world has no stdin; a served handler's fd_read is EOF
		// by construction, so there is nothing to migrate.
		List<LispVal> program = LispReader.readAllFromString(
				"(rontolisp:async-defun h (env) (list 200 nil (list (read-line))))\n(rontolisp:http-handler 'h)");
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
		assertThat(definesInternal(out, "RONTOLISP::%STDIN-READ-LINE-OR-RAW-F")).isTrue();
		assertThat(definesInternal(out, "RONTOLISP::%STDIN-READ-LINE-F")).isTrue();
		assertThat(countDefuns(out, "RONTOLISP::%IO-READ-LINE")).isEqualTo(1);
	}

	@Test
	void socketsSplicedServeProgramGetsTheStub() {
		// A serve+tcp program still needs the or-raw helper names sockets.lisp's
		// dispatchers reference, but as raw passthroughs: the service world has no
		// wasi:cli/stdin to bind, so the real machinery (and its wit-import) must be
		// absent.
		List<LispVal> program = SocketsLibrary.process(LispReader.readAllFromString("""
				(defun h (env) (list 200 nil (list (rontolisp:tcp-peer-address 0))))
				(rontolisp:http-handler 'h)
				"""), COMPONENT);
		List<LispVal> out = StdinLibrary.process(program, COMPONENT, true);
		assertThat(definesInternal(out, "RONTOLISP::%STDIN-READ-LINE-OR-RAW-F")).isTrue();
		assertThat(definesInternal(out, "RONTOLISP::%STDIN-READ-LINE-F")).isFalse();
	}

	@Test
	void theTwoDispatchSplicesDefineTheSameNamesAndShapes() {
		// stdin-dispatch.lisp and sockets.lisp are ALTERNATIVE providers of the same
		// %io-* / %*-future dispatch names -- exactly one of them is spliced. The WASM
		// socket/stdin I/O rewrite picks its target by CALL SHAPE and cannot know which
		// file it got, so a name or an argument shape that only one file defines is a
		// compile failure ("the function RONTOLISP::%IO-... is undefined", or an arity
		// error) for every program on the other splice. That is not hypothetical: the
		// bounded sequence ops and the eof-carrying read-char/read-line landed in
		// sockets.lisp first and broke every async stdin component until this file
		// caught up. Compare the name -> (required, optional) shape maps, not just the
		// names.
		Map<String, String> sockets = dispatchShapes(
				SocketsLibrary.process(LispReader.readAllFromString("(close (rontolisp:tcp-listen 7777))"), COMPONENT));
		Map<String, String> stdin = dispatchShapes(StdinLibrary.process(
				LispReader.readAllFromString(
						"(rontolisp:async-defun main () (print (read-line)))\n" + "(rontolisp:await (main))"),
				COMPONENT, false));
		assertThat(stdin).isNotEmpty();
		assertThat(stdin).containsAllEntriesOf(sockets);
	}

	// name -> "required/optional" for every dispatch defun a splice provides. Only the
	// names the rewrite can target are compared; each file is free to carry its own
	// internals (sockets.lisp's %sock-* helpers) beside them.
	private static Map<String, String> dispatchShapes(List<LispVal> forms) {
		Map<String, String> shapes = new java.util.LinkedHashMap<>();
		for (LispVal form : forms) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)
					|| !("DEFUN".equals(head.name()) || "RONTOLISP:ASYNC-DEFUN".equals(head.name()))
					|| !(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispSymbol name)
					|| !(rest.cdr() instanceof LispCons afterName)) {
				continue;
			}
			String member = name.name();
			if (!member.startsWith("RONTOLISP::%IO-") && !member.endsWith("-FUTURE")) {
				continue;
			}
			int required = 0;
			int optional = 0;
			boolean seenOptional = false;
			if (afterName.car() instanceof LispCons params && params.isProperList()) {
				for (LispVal param : params.toList()) {
					if (param instanceof LispSymbol p && "&OPTIONAL".equals(p.name())) {
						seenOptional = true;
					}
					else if (seenOptional) {
						optional++;
					}
					else {
						required++;
					}
				}
			}
			shapes.put(member, required + "/" + optional);
		}
		return shapes;
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
					&& ("DEFUN".equals(head.name()) || "RONTOLISP:ASYNC-DEFUN".equals(head.name()))
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol defunName
					&& name.equals(defunName.name())) {
				count++;
			}
		}
		return count;
	}

}
