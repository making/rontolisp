package am.ik.rontolisp.cli;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpHandlerInlinerTest {

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	@Test
	void usesHttpHandlerDetectsTheDirective() {
		// The class is now only the presence check the CLI routes on; the HTTP glue is
		// serve.lisp (eval/ServeLibrary), covered by the serve integration tests.
		assertThat(HttpHandlerInliner.usesHttpHandler(read("(defun h (r) nil) (rontolisp:http-handler 'h)"))).isTrue();
		assertThat(HttpHandlerInliner.usesHttpHandler(read("(defun h (r) nil)"))).isFalse();
	}

}
