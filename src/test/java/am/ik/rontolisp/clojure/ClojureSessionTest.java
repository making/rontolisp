package am.ik.rontolisp.clojure;

import java.util.List;

import am.ik.rontolisp.LispVal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClojureSessionTest {

	@Test
	void aLaterBufferCallsWhatAnEarlierOneDefined() {
		ClojureSession session = new ClojureSession();
		assertThat(session.read("(defn twice [x] (* 2 x))").get(0).forms().stream().map(LispVal::print).toList())
			.containsExactly("(DEFUN |c%twice| (|c%x|) (* 2 |c%x|))");
		List<ClojureTopLevel> call = session.read("(twice 21)");
		assertThat(call.get(0).forms().stream().map(LispVal::print).toList()).containsExactly("(|c%twice| 21)");
	}

	@Test
	void isCompleteCountsBracketsStringsAndComments() {
		assertThat(ClojureSession.isComplete("(defn f [x] x)")).isTrue();
		assertThat(ClojureSession.isComplete("(defn f [x]")).isFalse();
		assertThat(ClojureSession.isComplete("[1 2")).isFalse();
		assertThat(ClojureSession.isComplete("{:a 1")).isFalse();
		assertThat(ClojureSession.isComplete("\"abc")).isFalse();
		assertThat(ClojureSession.isComplete("(defn f [x] x) ; comment")).isTrue();
		assertThat(ClojureSession.isComplete("(a b")).isFalse();
		// Complete but wrong still answers true: the error belongs to read.
		assertThat(ClojureSession.isComplete("(nope 1)")).isTrue();
		assertThat(ClojureSession.isComplete("'")).isFalse();
	}

}
