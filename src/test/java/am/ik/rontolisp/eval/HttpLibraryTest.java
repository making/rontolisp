package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpLibraryTest {

	@Test
	void referencesFetchSeesCanonicalName() {
		List<LispVal> program = LispReader.readAllFromString("(rontolisp:fetch \"https://example.com\")");
		assertThat(HttpLibrary.referencesFetch(program)).isTrue();
	}

	@Test
	void referencesFetchSeesBuiltinNickname() {
		// rl: is a built-in nickname of rontolisp; the splice scan runs before
		// PackageResolver normalizes it, so the scan must normalize itself.
		List<LispVal> program = LispReader.readAllFromString("(rl:fetch \"https://example.com\")");
		assertThat(HttpLibrary.referencesFetch(program)).isTrue();
	}

	@Test
	void referencesFetchSeesDoubleColonSpelling() {
		List<LispVal> program = LispReader.readAllFromString("(rontolisp::fetch \"https://example.com\")");
		assertThat(HttpLibrary.referencesFetch(program)).isTrue();
	}

	@Test
	void processSplicesFetchLibraryForNicknameReference() {
		List<LispVal> program = LispReader
			.readAllFromString("(princ (rl:await (getf (rl:await (rl:fetch \"https://example.com\")) :status)))");
		List<LispVal> out = HttpLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, false);
		assertThat(definesDefun(out, "RONTOLISP:FETCH")).isTrue();
	}

	@Test
	void processDoesNotSpliceWhenProgramDefinesFetchUnderNickname() {
		// The dedup guard must also recognize a user defun spelled through the
		// nickname, or the splice would collide with it.
		List<LispVal> program = LispReader
			.readAllFromString("(defun rl:fetch (url) url)\n(rl:fetch \"https://example.com\")");
		List<LispVal> out = HttpLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, false);
		assertThat(out).isEqualTo(program);
	}

	@Test
	void processDoesNotSpliceWhenProgramAsyncDefinesFetchUnderNickname() {
		List<LispVal> program = LispReader
			.readAllFromString("(rl:async-defun rontolisp:fetch (url) url)\n(rontolisp:fetch \"https://example.com\")");
		List<LispVal> out = HttpLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, false);
		assertThat(out).isEqualTo(program);
	}

	@Test
	void processExtractsANestedHttpHandlerCallAndLowersTheCallSiteToNil() {
		// The clack-handler-rontolisp shape: the directive is called INSIDE a defun
		// body with a literal quoted (package-qualified) handler name. The name must
		// reach the export wiring and the call site must become nil -- the host owns
		// the socket, so run returns at once and requests arrive via the exported
		// handle.
		List<LispVal> program = LispReader.readAllFromString("""
				(defun my.pkg::%bridge (req) req)
				(defun my.pkg:run (app port)
				  (rontolisp:http-handler 'my.pkg::%bridge port))
				(my.pkg:run (lambda (env) env) 5000)
				""");
		List<LispVal> out = HttpLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, true);
		// The bridge defun dispatches to the extracted handler name.
		assertThat(definesDefun(out, "%SERVE-DISPATCH")).isTrue();
		String printed = printAll(out);
		assertThat(printed).contains("(MY.PKG::%BRIDGE %SERVE-REQ)");
		// The nested call site itself is gone (only the bridge references the
		// directive's machinery).
		assertThat(printed).doesNotContain("RONTOLISP:HTTP-HANDLER");
	}

	@Test
	void processLeavesAQuotedHttpHandlerFormAlone() {
		// Quoted data is not a call site: nothing to extract, nothing rewritten.
		List<LispVal> program = LispReader.readAllFromString("""
				(defvar *x* '(rontolisp:http-handler 'my.pkg::%bridge 5000))
				""");
		List<LispVal> out = HttpLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, true);
		assertThat(out).isEqualTo(program);
	}

	@Test
	void processKeepsTheTopLevelDirectiveShapeWorking() {
		List<LispVal> program = LispReader.readAllFromString("""
				(defun my-handler (req) req)
				(rontolisp:http-handler 'my-handler 8080)
				""");
		List<LispVal> out = HttpLibrary.process(program, WitExportDirective.Backend.WASM_COMPONENT, true);
		assertThat(definesDefun(out, "%SERVE-DISPATCH")).isTrue();
		assertThat(printAll(out)).contains("(MY-HANDLER %SERVE-REQ)");
	}

	private static String printAll(List<LispVal> forms) {
		StringBuilder sb = new StringBuilder();
		for (LispVal form : forms) {
			sb.append(form.print()).append('\n');
		}
		return sb.toString();
	}

	static boolean definesDefun(List<LispVal> forms, String name) {
		for (LispVal form : forms) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol head && "DEFUN".equals(head.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol defunName
					&& name.equals(defunName.name())) {
				return true;
			}
		}
		return false;
	}

}
