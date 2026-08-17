package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): Kan-Ru Chen's REAL cl-mustache
 * sources (vendored unmodified under {@code src/test/resources/cl-mustache}, MIT/Expat)
 * load via {@code asdf:load-system} and render Mustache templates. Runs on all four
 * backends via {@link AsdfLibraryE2eSupport}.
 *
 * <p>
 * This exercise walks the whole public surface the library documents -- {@code version},
 * {@code render}, {@code render*}, {@code compile-template}, {@code define} and
 * {@code make-context} -- over string AND file templates, alist AND hash-table contexts,
 * sections, inverted sections, partials, lambdas and dynamic partial names. Three of the
 * shapes here are the ones that used to differ per backend, so they are the point of the
 * test rather than decoration:
 *
 * <ul>
 * <li>a hash-table context: {@code context-get} is a {@code defmethod} whose body IS
 * {@code (gethash ...)}, so the whole library reads a variable through a
 * FUNCTION-RETURNED second value ({@code .kb/multiple-values.md}),</li>
 * <li>a file template compiled at run time from a path the program itself wrote, which on
 * WASM has to resolve against the preopen table ({@code .kb/read-load-streams.md}),</li>
 * <li>a missing partial, which {@code signal}s {@code partial-cant-be-found} with a
 * {@code use-value} restart -- caught here both by {@code handler-case} (the signal must
 * not be fatal on the compile paths) and by {@code handler-bind} +
 * {@code use-value}.</li>
 * </ul>
 *
 * <p>
 * The upstream spec suite is pinned separately by {@link ClMustacheSpecE2eTest}.
 */
class ClMustacheE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "cl-mustache")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :cl-mustache)
			(princ (mustache:version))
			(terpri)
			(princ (mustache:render* "Hello, {{name}}!" '((:name . "World"))))
			(terpri)
			(princ (with-output-to-string (mustache:*output-stream*)
			         (mustache:render "{{greeting}}, {{name}}!" '((:greeting . "Hi") (:name . "mustache")))))
			(terpri)
			(princ (mustache:render* "{{html}}|{{{html}}}" '((:html . "<b>&</b>"))))
			(terpri)
			(princ (mustache:render* "{{#items}}[{{name}}x{{qty}}]{{/items}}"
			                         '((:items . (((:name . "pen") (:qty . 2)) ((:name . "ink") (:qty . 3)))))))
			(terpri)
			(princ (mustache:render* "{{^stock}}sold out{{/stock}}{{#stock}}in stock{{/stock}}" '((:stock . nil))))
			(terpri)
			(princ (mustache:render* "{{var}}" (let ((h (make-hash-table :test #'equal)))
			                                     (setf (gethash "VAR" h) "from-hash")
			                                     h)))
			(terpri)
			(princ (mustache:render* "{{>greet}}!" (mustache:make-context :data '((:name . "Ronto"))
			                                                             :partials '(("greet" . "Hello, {{name}}")))))
			(terpri)
			(princ (mustache:render* "[{{>*which}}]" (mustache:make-context :data '((:which . "b"))
			                                                               :partials '(("a" . "A") ("b" . "B")))))
			(terpri)
			(princ (mustache:render* "{{#shout}}hello{{/shout}}" (list (cons :shout (lambda (text) (string-upcase text))))))
			(terpri)
			(mustache:define greeting-template "Defined: {{x}}")
			(princ (with-output-to-string (mustache:*output-stream*) (greeting-template '((:x . 42)))))
			(terpri)
			(let ((fn (mustache:compile-template "compiled {{a}}/{{b}}")))
			  (princ (with-output-to-string (out) (funcall fn '((:a . 1) (:b . 2)) out))))
			(terpri)
			(with-open-file (s "target/mustache-e2e.mustache" :direction :output :if-exists :supersede)
			  (write-string "file says {{who}}" s))
			(princ (with-output-to-string (out)
			         (funcall (mustache:compile-template (pathname "target/mustache-e2e.mustache")) '((:who . "hi")) out)))
			(terpri)
			(princ (handler-case (mustache:render* "{{>nope}}" (mustache:make-context :data nil :partials nil))
			         (mustache:partial-cant-be-found () "no such partial")))
			(terpri)
			(princ (handler-bind ((mustache:partial-cant-be-found (lambda (c) (declare (ignore c)) (use-value "{{fallback}}"))))
			         (mustache:render* "<{{>nope}}>" (mustache:make-context :data '((:fallback . "used")) :partials nil))))
			(terpri)
			""";

	private static final List<String> EXPECTED = List.of("CL-MUSTACHE 0.12.3 (Mustache spec 1.1.2, including lambdas)",
			"Hello, World!", "Hi, mustache!", "&lt;b&gt;&amp;&lt;/b&gt;|<b>&</b>", "[penx2][inkx3]", "sold out",
			"from-hash", "Hello, Ronto!", "[B]", "HELLO", "Defined: 42", "compiled 1/2", "file says hi",
			"no such partial", "<used>");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "TestClMustache";
	}

}
