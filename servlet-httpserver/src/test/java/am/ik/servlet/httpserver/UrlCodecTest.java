package am.ik.servlet.httpserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlCodecTest {

	/**
	 * The example paths of the Servlet 6.1 specification, 3.5.3, with the path each is
	 * decoded to; none is one of those it rejects.
	 */
	@ParameterizedTest
	@CsvSource(textBlock = """
			/foo/bar, /foo/bar
			/foo/bar;jsessionid=1234, /foo/bar
			/foo/bar/, /foo/bar/
			/foo/bar/;jsessionid=1234, /foo/bar/
			/foo;/bar;, /foo/bar
			/foo;/bar;/;, /foo/bar/
			/foo/b%25r, /foo/b%r
			/foo/./bar, /foo/bar
			/foo/././bar, /foo/bar
			/./foo/bar, /foo/bar
			/foo/bar/., /foo/bar
			/foo/bar/./, /foo/bar/
			/foo/.bar, /foo/.bar
			/foo/../bar, /bar
			/foo/./../bar, /bar
			/foo/bar/.., /foo
			/foo/bar/../, /foo/
			/foo/..bar, /foo/..bar
			/foo/.../bar, /foo/.../bar
			/foo//bar, /foo/bar
			//foo//bar//, /foo/bar/
			/foo//../bar, /bar
			/foo%E2%82%ACbar, /foo€bar
			/foo%20bar, /foo bar
			/, /
			//, /
			/., /
			/./, /
			""")
	void aPathIsMadeCanonicalAsTheSpecificationSays(String encoded, String decoded) {
		assertThat(UrlCodec.canonicalPath(encoded)).isEqualTo(decoded);
	}

	/** The example paths the specification rejects, 3.5.3. */
	@ParameterizedTest
	@CsvSource(textBlock = """
			foo/bar
			/foo%00/bar/
			/foo%7Fbar
			/foo%2Fbar
			/foo%2Fb%25r
			/foo\\bar
			/foo%5Cbar
			/foo;%2F/bar
			/foo/%2e/bar
			/foo/.;/bar
			/foo/%2e;/bar
			/foo/.%2Fbar
			/foo/.%5Cbar
			/foo/bar/.;
			/foo/../../bar
			/../foo/bar
			/foo/%2e%2E/bar
			/foo/%2e%2e/%2E%2E/bar
			/foo/..;/bar
			/foo/%2e%2E;/bar
			/foo/..%2Fbar
			/foo/..%5Cbar
			/foo/bar/..;
			/;/foo;/;/bar/;/;
			/foo/;/../bar
			/foo%E2%82
			/foo%E2%82bar
			/foo%-1/bar
			/foo%XX/bar
			/foo%/bar
			/foo/bar%0
			/good%20/bad%/%20mix%
			/;/
			/..
			/../
			./foo/bar/
			;/foo/bar/
			""")
	void aSuspiciousPathIsRejected(String encoded) {
		assertThat(UrlCodec.canonicalPath(encoded)).isNull();
	}

}
