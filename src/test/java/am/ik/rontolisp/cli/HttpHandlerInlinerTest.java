package am.ik.rontolisp.cli;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpHandlerInlinerTest {

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	@Test
	void usesHttpHandlerDetectsTheDirective() {
		assertThat(HttpHandlerInliner.usesHttpHandler(read("(defun h (r) nil) (rontolisp:http-handler 'h)"))).isTrue();
		assertThat(HttpHandlerInliner.usesHttpHandler(read("(defun h (r) nil)"))).isFalse();
	}

	@Test
	void inlineSplicesDispatchWrapperAndDropsDirective() {
		List<LispVal> out = HttpHandlerInliner
			.inline(read("(defun handle (r) (list :status 200 :body \"hi\")) (rontolisp:http-handler 'handle 8080)"));
		String printed = out.stream().map(LispVal::print).reduce("", (a, b) -> a + "\n" + b);
		// The http-handler directive is gone.
		assertThat(printed).doesNotContain("http-handler");
		// The dispatch wrapper (calling the user handler) and the wasm-export directive
		// are
		// spliced in.
		assertThat(printed).contains("%http-dispatch");
		assertThat(printed).contains("(handle (list");
		assertThat(printed).contains("wasm-export");
		// The user's own defun is preserved.
		assertThat(printed).contains("(defun handle");
	}

	@Test
	void inlineWithoutDirectiveIsUnchanged() {
		List<LispVal> program = read("(defun handle (r) nil)");
		assertThat(HttpHandlerInliner.inline(program)).isSameAs(program);
	}

	@Test
	void inlineRejectsNonQuotedHandler() {
		assertThatThrownBy(() -> HttpHandlerInliner.inline(read("(rontolisp:http-handler handle)")))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("quoted handler name");
	}

}
