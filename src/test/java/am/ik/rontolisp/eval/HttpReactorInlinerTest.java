package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpReactorInlinerTest {

	// The shape BOTH Clack handler backends' run has (clack-handler-reactor on every
	// WASM compile, clack-handler-rontolisp under #+rontolisp-reactor): the marker is
	// NESTED in a defun, not a top-level directive, and it names the ONE shared
	// dispatcher of http-reactor.lisp.
	private static final String SHIM = """
			(defun clack.handler.reactor:run (app &rest ignored)
			  (declare (ignore ignored))
			  (rontolisp::%http-reactor-register app)
			  (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch
			                            "handle-request")
			  nil)
			""";

	@Test
	void synthesizesTheExportFromANestedMarker() {
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM),
				WitExportDirective.Backend.WASM_GC);
		String printed = print(out);
		// The bridge and its export are APPENDED after the program, so a
		// package-qualified dispatcher name resolves whatever package it ended in.
		assertThat(printed).contains("(DEFUN %REACTOR-DISPATCH (%REACTOR-JSON) "
				+ "(RONTOLISP::%HTTP-REACTOR-DISPATCH %REACTOR-JSON (FUNCTION %REACTOR-READ-CHUNK)))");
		assertThat(printed).contains("(RONTOLISP:WASM-EXPORT (QUOTE %REACTOR-DISPATCH) :AS \"handle-request\" "
				+ ":PARAMS (QUOTE (:STRING)) :RETURNS :STRING)");
	}

	@Test
	void thePreview1BridgeTakesTheBodyOutOfTheEnvelope() {
		String printed = print(
				HttpReactorInliner.process(LispReader.readAllFromString(SHIM), WitExportDirective.Backend.WASM_GC));
		// The head still crosses as the JSON string; the body crosses as octets through
		// a caller-buffered :bytes import, declared suspending so a host that STREAMS
		// the upload is a supported host rather than a silent re-entrancy hazard.
		assertThat(printed).contains("(RONTOLISP:WASM-IMPORT (QUOTE %REACTOR-READ-BODY) :FROM \"env\" "
				+ ":AS \"readRequestBody\" :PARAMS (QUOTE NIL) :RETURNS :BYTES :ASYNC T)");
		// The import is CALLED, never taken as #'value: the build's suspending-import
		// report follows calls, and an escaped import widens it to "any export".
		assertThat(printed).contains("(%REACTOR-READ-BODY %REACTOR-BUF)")
			.contains("(RONTOLISP::%HTTP-REACTOR-DISPATCH %REACTOR-JSON (FUNCTION %REACTOR-READ-CHUNK))");
	}

	@Test
	void theComponentBridgeKeepsTheInBandBody() {
		// A :bytes import is a wasm-import over a packed array, and --component has
		// neither -- so a reactor component keeps the whole body inside the envelope's
		// "body" key, which the transport still accepts. Same for --no-gc, which cannot
		// carry the HTTP transport at all.
		for (WitExportDirective.Backend backend : List.of(WitExportDirective.Backend.WASM_COMPONENT,
				WitExportDirective.Backend.WASM_NO_GC)) {
			String printed = print(HttpReactorInliner.process(LispReader.readAllFromString(SHIM), backend));
			assertThat(printed).as("%s", backend)
				.doesNotContain("WASM-IMPORT")
				.contains("(RONTOLISP::%HTTP-REACTOR-DISPATCH %REACTOR-JSON)");
		}
	}

	@Test
	void lowersTheMarkerCallSiteButKeepsTheAppStore() {
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM),
				WitExportDirective.Backend.WASM_GC);
		// Nothing defines %http-reactor -- leaving the call in would be an undefined
		// function at run time. The register call around it must survive; the marker
		// itself (the name with no trailing dash) must be gone.
		assertThat(print(out)).doesNotContain("(RONTOLISP::%HTTP-REACTOR (QUOTE")
			.contains("(RONTOLISP::%HTTP-REACTOR-REGISTER APP)");
	}

	@Test
	void twoIdenticalMarkersSynthesizeOneBridge() {
		// A program can splice BOTH handler backends (clack always brings
		// clack-handler-rontolisp; the user quickloads the reactor one on top).
		// Under a reactor compile each run carries the marker, both naming the shared
		// dispatcher -- one bridge, one export, whichever is read first.
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM + """
				(defun clack.handler.rontolisp:run (app &rest ignored)
				  (declare (ignore ignored))
				  (rontolisp::%http-reactor-register app)
				  (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch
				                            "handle-request"))
				"""), WitExportDirective.Backend.WASM_GC);
		String printed = print(out);
		assertThat(printed).doesNotContain("(RONTOLISP::%HTTP-REACTOR (QUOTE");
		int first = printed.indexOf("(DEFUN %REACTOR-DISPATCH");
		assertThat(first).isNotNegative();
		assertThat(printed.indexOf("(DEFUN %REACTOR-DISPATCH", first + 1)).isNegative();
	}

	@Test
	void doesNotSynthesizeWhenTheProgramAlreadyExportsThatName() {
		// The pre-clackup shape: the user writes the wasm-export and calls `handle`
		// from it, so the marker's export name is already taken. Synthesizing anyway
		// emits a module with a DUPLICATE export name, which no engine will compile.
		List<LispVal> out = HttpReactorInliner.process(LispReader.readAllFromString(SHIM + """
				(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
				(defun handle-request (json) (clack.handler.reactor:handle #'app json))
				"""), WitExportDirective.Backend.WASM_GC);
		String printed = print(out);
		assertThat(printed).doesNotContain("%REACTOR-DISPATCH");
		// The marker still has to go: nothing defines it. (The register call and the
		// dispatcher NAME legitimately stay -- only the marker form itself, the bare
		// %HTTP-REACTOR operator, must be gone.)
		assertThat(printed).doesNotContain("(RONTOLISP::%HTTP-REACTOR (QUOTE");
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
	void theLoweredHttpHandlerDirectiveKeepsItsRawBodyMode() {
		// The port is dropped (a reactor host owns the listening side), but the
		// :raw-body mode is NOT: it rides the registration, so the directive's default
		// -- rontolisp's own asynchronous body -- reaches the transport instead of the
		// reactor silently buffering, and :buffered still asks for the Clack shape.
		List<LispVal> streaming = HttpReactorInliner
			.lowerHttpHandler(LispReader.readAllFromString("(rontolisp:http-handler 'handle 8080)"));
		assertThat(print(streaming)).contains("(RONTOLISP::%HTTP-REACTOR-REGISTER (FUNCTION HANDLE))");

		List<LispVal> buffered = HttpReactorInliner.lowerHttpHandler(
				LispReader.readAllFromString("(rontolisp:http-handler 'handle 8080 :raw-body :buffered)"));
		assertThat(print(buffered)).contains("(RONTOLISP::%HTTP-REACTOR-REGISTER (FUNCTION HANDLE) :BUFFERED)");
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
