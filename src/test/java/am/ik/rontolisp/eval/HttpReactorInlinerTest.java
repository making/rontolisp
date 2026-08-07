package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpReactorInlinerTest {

	// The shape the clack-handler-cloudflare-workers shim's run has: the marker is
	// NESTED in a defun, not a top-level directive.
	private static final String SHIM = """
			(defun clack.handler.cloudflare-workers:run (app &rest ignored)
			  (declare (ignore ignored))
			  (setq clack.handler.cloudflare-workers::*app* app)
			  (rontolisp::%http-reactor 'clack.handler.cloudflare-workers:dispatch
			                            "handle-request")
			  nil)
			""";

	@Test
	void synthesizesTheExportFromANestedMarker() {
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM),
				WitExportDirective.Backend.WASM_GC);
		String printed = print(out);
		// The bridge and its export are APPENDED, so a package-qualified dispatcher
		// name resolves against the shim's own defpackage.
		assertThat(printed).contains("(DEFUN %REACTOR-DISPATCH (%REACTOR-JSON) "
				+ "(CLACK.HANDLER.CLOUDFLARE-WORKERS:DISPATCH %REACTOR-JSON))");
		assertThat(printed).contains("(RONTOLISP:WASM-EXPORT (QUOTE %REACTOR-DISPATCH) :AS \"handle-request\" "
				+ ":PARAMS (QUOTE (:STRING)) :RETURNS :STRING)");
	}

	@Test
	void lowersTheMarkerCallSiteButKeepsTheAppStore() {
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM),
				WitExportDirective.Backend.WASM_GC);
		// Nothing defines %http-reactor -- leaving the call in would be an undefined
		// function at run time. The setq around it must survive.
		assertThat(print(out)).doesNotContain("%HTTP-REACTOR")
			.contains("(SETQ CLACK.HANDLER.CLOUDFLARE-WORKERS::*APP* APP)");
	}

	@Test
	void doesNotSynthesizeWhenTheProgramAlreadyExportsThatName() {
		// The pre-clackup shape: the user writes the wasm-export and calls `handle`
		// from it, so the marker's export name is already taken. Synthesizing anyway
		// emits a module with a DUPLICATE export name, which no engine will compile.
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM + """
				(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
				(defun handle-request (json) (clack.handler.cloudflare-workers:handle #'app json))
				"""), WitExportDirective.Backend.WASM_GC);
		String printed = print(out);
		assertThat(printed).doesNotContain("%REACTOR-DISPATCH");
		// The marker still has to go: nothing defines it.
		assertThat(printed).doesNotContain("%HTTP-REACTOR");
	}

	@Test
	void doesNotSynthesizeWhenTheNameIsTakenThroughAnAsAlias() {
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM + """
				(rontolisp:wasm-export 'my-entry :as "handle-request"
				                       :params '(:string) :returns :string)
				(defun my-entry (json) json)
				"""), WitExportDirective.Backend.WASM_GC);
		assertThat(print(out)).doesNotContain("%REACTOR-DISPATCH");
	}

	@Test
	void stillSynthesizesBesideAnUnrelatedExport() {
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM + """
				(rontolisp:wasm-export 'ping :returns :int)
				(defun ping () 42)
				"""), WitExportDirective.Backend.WASM_GC);
		assertThat(print(out)).contains("%REACTOR-DISPATCH");
	}

	@Test
	void isANoOpOnTheInterpreterAndJvm() {
		// There the shim never even reads the marker (#+rontolisp-wasm); this guards
		// the case where some other source carries one.
		List<LispVal> program = LispReader.readAllFromString(SHIM);
		assertThat(HttpReactorInliner.process(program, WitExportDirective.Backend.OTHER)).isSameAs(program);
	}

	@Test
	void isANoOpWithoutAMarker() {
		List<LispVal> program = LispReader.readAllFromString("(defun f (x) x)");
		assertThat(HttpReactorInliner.process(program, WitExportDirective.Backend.WASM_GC)).isSameAs(program);
	}

	@Test
	void leavesQuotedDataAlone() {
		List<LispVal> program = LispReader
			.readAllFromString("(defvar *x* '(rontolisp::%http-reactor 'dispatch \"handle-request\"))");
		assertThat(HttpReactorInliner.process(program, WitExportDirective.Backend.WASM_GC)).isSameAs(program);
	}

	@Test
	void rejectsANonLiteralExportName() {
		List<LispVal> program = LispReader.readAllFromString("(defun run (a) (rontolisp::%http-reactor 'dispatch a))");
		assertThatThrownBy(() -> HttpReactorInliner.process(program, WitExportDirective.Backend.WASM_GC))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("literal string export name");
	}

	private static String print(List<LispVal> forms) {
		StringBuilder sb = new StringBuilder();
		for (LispVal form : forms) {
			sb.append(form.print()).append('\n');
		}
		return sb.toString();
	}

}
