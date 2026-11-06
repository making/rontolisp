package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * trivial-mimes 1.1.0 (vendored unmodified under
 * {@code src/test/resources/trivial-mimes}, zlib) on all four backends via
 * {@link AsdfLibraryE2eSupport}. Its load builds the MIME table from
 * {@code /etc/mime.types} or the {@code mime.types} it bundles, and signalled when it
 * found neither; the WASM legs preopen only their working directory, so they run at all
 * only because the derived form {@code eval/TrivialMimesForms} ({@code .kb/asdf.md}, tier
 * 4) carries the bundled table into the module. Every answer below is the same from
 * either file.
 */
class TrivialMimesE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "trivial-mimes")
		.toAbsolutePath()
		.toString();

	private static final String EXERCISE = """
			(asdf:load-system :trivial-mimes)
			(print (> (hash-table-count mimes:*mime-db*) 900))
			(print (mapcar #'mimes:mime-lookup (list "a.html" "b.png" "c.css" "d.json" #p"e.pdf")))
			(print (list (mimes:mime-file-type "image/png") (mimes:mime-file-type "application/json")))
			(print (list (mimes:mime "notes.no-such-type") (mimes:mime "notes.no-such-type" "text/plain")))
			(print (list (mimes:mime-equal "text/*" "text/html")
			             (mimes:mime-equal "text/html" "text/html;charset=utf-8")
			             (mimes:mime-equal "text/*" "application/json")))
			(print (mimes:mime-case "page.html"
			         (("application/json" "application/*") :opaque)
			         ("text/html" :html)
			         (t :unknown)))
			""";

	private static final List<String> EXPECTED = List.of("T",
			"(\"text/html\" \"image/png\" \"text/css\" \"application/json\" \"application/pdf\")", "(\"png\" \"json\")",
			"(\"application/octet-stream\" \"text/plain\")", "(T T NIL)", ":HTML");

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
		return "TrivialMimesE2e";
	}

}
