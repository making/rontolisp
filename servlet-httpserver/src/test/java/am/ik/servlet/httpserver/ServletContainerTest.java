package am.ik.servlet.httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import jakarta.servlet.AsyncContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One application asked the same requests in this module's container and in Tomcat and
 * Jetty. Where the three agree the specification is what they agree on; where they do
 * not, the test states what each one answered when it was measured (Tomcat 11.0.27, Jetty
 * 12.1.12), which is what this container's choice was made against.
 */
class ServletContainerTest {

	private static final String FORM = "Content-Type: application/x-www-form-urlencoded";

	private static final Map<Container, Container.Running> APPLICATION = new EnumMap<>(Container.class);

	private static final Map<Container, Container.Running> ROOT = new EnumMap<>(Container.class);

	private static final Map<Container, Application> APPLICATIONS = new EnumMap<>(Container.class);

	@BeforeAll
	static void start() throws Exception {
		for (Container container : Container.values()) {
			Application application = new Application();
			APPLICATIONS.put(container, application);
			APPLICATION.put(container, container.start("/app", application::register));
			ROOT.put(container, container.start("", new Application()::registerRoot));
		}
	}

	@AfterAll
	static void stop() throws Exception {
		for (Container.Running running : APPLICATION.values()) {
			running.stop();
		}
		for (Container.Running running : ROOT.values()) {
			running.stop();
		}
	}

	private static Http.Reply get(Container container, String target, String... fields) throws IOException {
		return Http.get(APPLICATION.get(container).port, target, fields);
	}

	private static Http.Reply send(Container container, String method, String target, String body, String... fields)
			throws IOException {
		return Http.send(APPLICATION.get(container).port, method, target,
				body == null ? null : body.getBytes(StandardCharsets.UTF_8), fields);
	}

	private static <T> T measured(Container container, T httpserver, T tomcat, T jetty) {
		return container == Container.HTTPSERVER ? httpserver : container == Container.TOMCAT ? tomcat : jetty;
	}

	/** The status, the fields that frame and type the body, and the body. */
	private static String told(Http.Reply reply) {
		StringBuilder told = new StringBuilder().append(reply.status);
		for (String name : new String[] { "Content-Type", "Content-Length", "Transfer-Encoding", "Location" }) {
			if (reply.field(name) != null) {
				told.append(' ').append(name).append(": ").append(reply.field(name));
			}
		}
		return told.append(" [").append(reply.text()).append(']').toString();
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void aPathIsMappedToItsServlet(Container container) throws Exception {
		String[][] mapped = { { "/app/exact?q=1&r",
				"uri=/app/exact context=/app servlet=/exact info=null query=q=1&r mapping=EXACT[exact][/exact][paths]" },
				{ "/app/exact/", "servlet=/exact/ info=null query=null mapping=DEFAULT[][/][default]" },
				{ "/app/prefix/", "servlet=/prefix info=/ query=null mapping=PATH[][/prefix/*][paths]" },
				{ "/app/prefix/a.ext", "servlet=/prefix info=/a.ext query=null mapping=PATH[a.ext][/prefix/*][paths]" },
				{ "/app/x/y.ext", "servlet=/x/y.ext info=null query=null mapping=EXTENSION[x/y][*.ext][paths]" },
				{ "/app/prefix.ext",
						"servlet=/prefix.ext info=null query=null mapping=EXTENSION[prefix][*.ext][paths]" },
				{ "/app/", "servlet= info=/ query=null mapping=CONTEXT_ROOT[][][paths]" },
				{ "/app/nothing", "servlet=/nothing info=null query=null mapping=DEFAULT[][/][default]" },
				// The path that is mapped is the canonical one; the URI stays as it was
				// sent.
				{ "/app/prefix/a%20b;p=1/c;q?x=1",
						"uri=/app/prefix/a%20b;p=1/c;q context=/app servlet=/prefix info=/a b/c query=x=1 mapping=PATH[a b/c][/prefix/*][paths]" },
				{ "/app/a/../pre%66ix/./z", "servlet=/prefix info=/z query=null mapping=PATH[z][/prefix/*][paths]" },
				{ "/app/prefix/..", "servlet= info=/ query=null mapping=CONTEXT_ROOT[][][paths]" } };
		for (String[] row : mapped) {
			assertThat(get(container, row[0]).text()).as(row[0]).contains(row[1]);
		}
		// A path prefix that is the whole path: the match value is the specification's
		// to leave open.
		assertThat(get(container, "/app/prefix").text()).endsWith("servlet=/prefix info=null query=null mapping=PATH["
				+ measured(container, "", "null", "") + "][/prefix/*][paths]");
		// Empty segments are removed (3.5.2); Jetty refuses them as ambiguous.
		assertThat(told(get(container, "/app//exact"))).startsWith(measured(container, "200", "200", "400"));
		// The context root without its slash: a redirect here and in Jetty, the root's
		// servlet in Tomcat.
		assertThat(told(get(container, "/app?q=1"))).startsWith(measured(container,
				"302 Content-Length: 0 Location: /app/?q=1", "200", "301 Content-Length: 0 Location: /app/?q=1"));
		// Rejected by the three: an encoded slash, a broken octet sequence, a
		// backslash, a control character. A path of another context is not found.
		for (String refused : new String[] { "/app/a%2Fb", "/app/prefix/%ff", "/app/a%5Cb", "/app/a%00b" }) {
			assertThat(get(container, refused).status).as(refused).isEqualTo(400);
		}
		assertThat(get(container, "/application").status).isEqualTo(404);
		assertThat(get(container, "/app/../x").status).isEqualTo(404);
		// Rejected by the specification's list and by Jetty; Tomcat resolves them.
		assertThat(get(container, "/app/%2e/exact").status).isEqualTo(measured(container, 400, 200, 400));
		assertThat(get(container, "/app/..;/x").status).isEqualTo(measured(container, 400, 404, 400));
		// A root context with no default servlet.
		int root = ROOT.get(container).port;
		assertThat(Http.get(root, "/prefix/a").text())
			.isEqualTo("uri=/prefix/a context= servlet=/prefix info=/a query=null mapping=PATH[a][/prefix/*][paths]");
		assertThat(Http.get(root, "/").text()).endsWith("servlet= info=/ query=null mapping=CONTEXT_ROOT[][][paths]");
		assertThat(Http.get(root, "/nothing").status).isEqualTo(404);
		assertThat(Http.get(root, "/../exact").status).isEqualTo(400);
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void aRequestTellsItsLineAndItsFields(Container container) throws Exception {
		Http.Reply reply = get(container, "/app/request/a?b=c", "X-One: 1", "X-Many: a", "X-Many: b, c", "X-Int: 42",
				"If-Modified-Since: Sun, 06 Nov 1994 08:49:37 GMT", "Cookie: a=1; b=\"two\"; z=", "Cookie: c=3",
				"Accept-Language: en;q=0.5, ja-JP, fr;q=0.8", "Host: example.com:8080");
		assertThat(reply.text().split("\n")).containsExactly("method=GET", "protocol=HTTP/1.1",
				"scheme=http secure=false", "server=example.com:8080", "url=http://example.com:8080/app/request/a",
				"remote=127.0.0.1 true", "local=127.0.0.1", "header=1 [a, b, c] null []",
				"names=[accept-language, connection, cookie, host, if-modified-since, x-int, x-many, x-one]",
				"int=42 -1", "date=784111777000 -1",
				// A quoted value keeps its quotes in RFC 6265; Jetty takes them off.
				"cookies=[a=1, b=" + measured(container, "\"two\"", "\"two\"", "two") + ", z=, c=3]",
				"locales=ja-JP [ja-JP, fr, en]", "content=null -1 -1 null", "context=/app before the start probe 6.1",
				"attributes=[a] 1 null", "session=null null false false REQUEST");
		// No port in Host is the scheme's; the two older forms of a date are read.
		assertThat(get(container, "/app/request", "Host: example.com").text())
			.contains("server=example.com:80\nurl=http://example.com/app/request\n")
			.contains("cookies=null\nlocales=true\n");
		assertThat(send(container, "POST", "/app/request", "hello", "Content-Type: text/plain; charset=UTF-8",
				"If-Modified-Since: Sunday, 06-Nov-94 08:49:37 GMT")
			.text()).contains("method=POST\n")
			.contains("date=784111777000 -1\n")
			.containsIgnoringCase("content=text/plain; charset=UTF-8 5 5 UTF-8\n");
		assertThat(get(container, "/app/request", "If-Modified-Since: Sun Nov  6 08:49:37 1994").text())
			.contains("date=784111777000 -1\n");
		// A field that is not what it is asked as is the servlet's exception.
		assertThat(get(container, "/app/request", "If-Modified-Since: yesterday").status).isEqualTo(500);
		assertThat(get(container, "/app/request", "X-Int: many").status).isEqualTo(500);
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void parametersComeFromTheQueryThenAPostedForm(Container container) throws Exception {
		assertThat(get(container, "/app/parameters?a=1&a=2&b=%E3%81%82&c&d=+%2B&e=").text())
			.isEqualTo("a=[1, 2];b=[あ];c=[];d=[ +];e=[]; first=1 none=null null map=5");
		// An escaped octet of a form with no charset: UTF-8 here and in Jetty, as the
		// query string's is everywhere; ISO-8859-1 in Tomcat.
		assertThat(send(container, "POST", "/app/parameters?a=q&z=9", "a=b1&f=%E3%81%82&a=b2", FORM).text())
			.isEqualTo("a=[q, b1, b2];z=[9];f=[" + measured(container, "あ", "ã\u0081\u0082", "あ")
					+ "]; first=q none=null null map=3");
		assertThat(send(container, "POST", "/app/parameters", "f=%82%A0", FORM + "; charset=Shift_JIS").text())
			.isEqualTo("f=[あ]; first=null none=null null map=1");
		assertThat(send(container, "POST", "/app/parameters", "f=%82%A0", FORM, "X-Mode: set-encoding").text())
			.isEqualTo(measured(container, "f=[あ]; first=null none=null null map=1",
					"f=[あ]; first=null none=null null map=1", "IllegalStateException"));
		// Only a POST's form, and only while the servlet has not taken the body (Jetty
		// reads the form in both cases).
		assertThat(send(container, "PUT", "/app/parameters?a=q", "f=1", FORM).text())
			.startsWith(measured(container, "a=[q]; ", "a=[q]; ", "a=[q];f=[1]; "));
		assertThat(send(container, "POST", "/app/parameters?a=q", "f=1", FORM, "X-Mode: stream-first").text())
			.startsWith(measured(container, "a=[q]; ", "a=[q]; ", "a=[q];f=[1]; "));
		assertThat(send(container, "POST", "/app/parameters?a=q", "f=1", "Content-Type: text/plain").text())
			.startsWith("a=[q]; ");
		assertThat(send(container, "POST", "/app/parameters?a=q", "f=1", FORM, "X-Mode: then-stream").text())
			.isEqualTo("a=[q];f=[1]; first=q none=null null map=2 left=-1");
		// What cannot be decoded is the IllegalStateException of Servlet 6.1.
		assertThat(send(container, "POST", "/app/parameters", "f=%zz", FORM).text()).isEqualTo("IllegalStateException");
		assertThat(send(container, "POST", "/app/parameters", "f=%4", FORM).text()).isEqualTo("IllegalStateException");
		assertThat(get(container, "/app/parameters?a=%E9").text()).isEqualTo("IllegalStateException");
		assertThat(send(container, "POST", "/app/parameters", "f=%E9", FORM).text()).isEqualTo(measured(container,
				"IllegalStateException", "f=[é]; first=null none=null null map=1", "IllegalStateException"));
		// A parameter with no name: one more parameter here and in Jetty, refused by
		// Tomcat.
		assertThat(get(container, "/app/parameters?=x&a=1").text())
			.isEqualTo(measured(container, "=[x];a=[1]; first=1 none=null null map=2", "IllegalStateException",
					"=[x];a=[1]; first=1 none=null null map=2"));
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void theBodyIsReadAsAStreamOrThroughAReader(Container container) throws Exception {
		String text = "héllo あ";
		String latin = new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
		assertThat(send(container, "POST", "/app/body", text).text())
			.isEqualTo("read 10 octets " + latin + " finished=true IllegalStateException");
		assertThat(send(container, "POST", "/app/body?as=reader", text).text())
			.isEqualTo("read " + latin + " again=true IllegalStateException");
		assertThat(
				send(container, "POST", "/app/body?as=reader", text, "Content-Type: text/plain; charset=UTF-8").text())
			.isEqualTo("read " + text + " again=true IllegalStateException");
		Http.Reply chunked = Http.raw(APPLICATION.get(container).port,
				("POST /app/body HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
						+ "3\r\nchu\r\n2\r\nnk\r\n0\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
		assertThat(chunked.text()).isEqualTo("read 5 octets chunk finished=true IllegalStateException");
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void aResponseIsBufferedUntilItIsCommitted(Container container) throws Exception {
		// What fits the buffer is sent with its length; an overflow or a flush commits,
		// and the rest is chunked (Jetty, asked to close, ends the connection instead).
		String chunked = measured(container, " Transfer-Encoding: chunked", " Transfer-Encoding: chunked", "");
		assertThat(told(get(container, "/app/response?case=small")))
			.isEqualToIgnoringCase("200 Content-Type: text/plain;charset=ISO-8859-1 Content-Length: 5 [hello]");
		assertThat(told(get(container, "/app/response?case=empty"))).isEqualTo("200 Content-Length: 0 []");
		Http.Reply large = get(container, "/app/response?case=large");
		assertThat(told(large)).startsWith("200 Content-Type: application/octet-stream" + chunked + " [xxxx");
		assertThat(large.text()).hasSize(20000).endsWith("xc");
		assertThat(told(get(container, "/app/response?case=flush"))).isEqualTo("200" + chunked
				+ " [before=false after=true reset=IllegalStateException resetBuffer=IllegalStateException"
				+ " setBufferSize=IllegalStateException]");
		// A declared length ends the response where it is reached (Jetty: a 500).
		assertThat(told(get(container, "/app/response?case=length")))
			.startsWith(measured(container, "200 Content-Length: 3 [abc]", "200 Content-Length: 3 [abc]", "500"));
		assertThat(get(container, "/app/response?case=both").text()).isEqualTo("getWriter=IllegalStateException");
		assertThat(get(container, "/app/response?case=both-writer").text())
			.isEqualTo("getOutputStream=IllegalStateException");
		Http.Reply reset = get(container, "/app/response?case=reset");
		assertThat(told(reset)).isEqualTo("200 Content-Length: 14 [clean 200 null]");
		assertThat(reset.field("X-Gone")).isNull();
		Http.Reply resetBuffer = get(container, "/app/response?case=resetBuffer");
		assertThat(told(resetBuffer)).isEqualTo("202 Content-Length: 5 [clean]");
		assertThat(resetBuffer.field("X-Kept")).isEqualTo("1");
		assertThat(get(container, "/app/response?case=buffer").text())
			.isEqualTo("usual=true asked=true written=IllegalStateException");
		assertThat(told(get(container, "/app/response?case=close"))).isEqualTo("200 Content-Length: 4 [kept]");
		// A HEAD is the GET without its body.
		assertThat(told(send(container, "HEAD", "/app/response?case=small", null)))
			.isEqualToIgnoringCase("200 Content-Type: text/plain;charset=ISO-8859-1 Content-Length: 5 []");
		assertThat(told(send(container, "HEAD", "/app/response?case=large", null)))
			.isEqualTo("200 Content-Type: application/octet-stream []");
		assertThat(told(get(container, "/app/response?case=no-content"))).isEqualTo("204 []");
		Http.Reply notModified = get(container, "/app/response?case=not-modified");
		assertThat(notModified.status).isEqualTo(304);
		assertThat(notModified.field("ETag")).isEqualTo("\"v1\"");
		assertThat(notModified.body).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void aResponseTellsItsFieldsAndItsType(Container container) throws Exception {
		Http.Reply fields = get(container, "/app/response?case=fields");
		assertThat(fields.field("X-Set")).isEqualTo("2");
		assertThat(fields.field("X-Add")).isEqualTo("1|2");
		assertThat(fields.field("X-Int")).isEqualTo("7|8");
		assertThat(fields.field("Last-Modified")).isEqualTo("Sun, 06 Nov 1994 08:49:37 GMT");
		assertThat(fields.field("X-Removed")).isNull();
		assertThat(fields.text()).startsWithIgnoringCase(
				"true false 1 [1, 2] [] null false false text/plain;charset=ISO-8859-1 [content-type, ");
		// The charset of a type is the one last set before the writer was taken, kept
		// as it was written; Jetty changes its case and leaves it out of a type set late.
		Http.Reply type = get(container, "/app/response?case=type");
		assertThat(told(type)).isEqualTo(measured(container,
				"200 Content-Type: text/csv;charset=utf-8 Content-Length: 156 [null ISO-8859-1 text/html ISO-8859-1"
						+ " text/html;charset=UTF-8 UTF-8 text/html;charset=Shift_JIS text/plain;charset=Shift_JIS"
						+ " text/csv;charset=utf-8 utf-8 あ]",
				"200 Content-Type: text/csv;charset=utf-8 Content-Length: 156 [null ISO-8859-1 text/html ISO-8859-1"
						+ " text/html;charset=UTF-8 UTF-8 text/html;charset=Shift_JIS text/plain;charset=Shift_JIS"
						+ " text/csv;charset=utf-8 utf-8 あ]",
				"200 Content-Type: text/csv Content-Length: 151 [null iso-8859-1 text/html;charset=utf-8 UTF-8"
						+ " text/html;charset=utf-8 utf-8 text/html;charset=Shift_JIS text/plain;charset=Shift_JIS"
						+ " text/csv utf-8 あ]"));
		// The writer makes the default encoding part of the type; the stream does not
		// (in Jetty it does).
		Http.Reply writer = get(container, "/app/response?case=writer-default");
		assertThat(writer.field("Content-Type")).isEqualToIgnoringCase("text/plain;charset=ISO-8859-1");
		assertThat(writer.body).endsWith((byte) ' ', (byte) 0xE9, (byte) '?');
		assertThat(get(container, "/app/response?case=no-type").text()).isEqualToIgnoringCase("null ISO-8859-1");
		assertThat(get(container, "/app/response?case=stream-type").field("Content-Type"))
			.isEqualTo(measured(container, "text/plain", "text/plain", "text/plain;charset=iso-8859-1"));
		assertThat(told(get(container, "/app/response?case=utf8"))).isEqualTo("200 Content-Length: 7 [😀あ]");
		assertThat(told(get(container, "/app/response?case=status"))).isEqualTo("201 Content-Length: 8 [made 201]");
		assertThat(get(container, "/app/response?case=locale").field("Content-Language")).isEqualTo("ja-JP");
		// A cookie is its pair and its attributes; the two others add an Expires to a
		// Max-Age and order the attributes their own way.
		String cookies = get(container, "/app/response?case=cookie").field("Set-Cookie");
		assertThat(cookies).startsWith("plain=value|full=v; ")
			.contains("; Domain=example.com", "; Max-Age=60", "; Path=/app", "; Secure", "; HttpOnly",
					"; SameSite=Lax");
		if (container == Container.HTTPSERVER) {
			assertThat(cookies).isEqualTo(
					"plain=value|full=v; Domain=example.com; HttpOnly; Max-Age=60; Path=/app; SameSite=Lax; Secure");
		}
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void anErrorOrARedirectEndsTheResponse(Container container) throws Exception {
		// The buffer is replaced by the container's page, the fields set before stay,
		// what is written or set afterwards is dropped (Jetty keeps a field set after).
		Http.Reply error = get(container, "/app/errors?case=error");
		assertThat(error.status).isEqualTo(404);
		assertThat(error.field("X-Before")).isEqualTo("1");
		assertThat(error.field("X-After")).isEqualTo(measured(container, null, null, "1"));
		assertThat(error.text()).contains("404").doesNotContain("junk", "dropped");
		assertThat(get(container, "/app/errors?case=message").text()).contains("&lt;teapot&gt; &amp; &quot;cup&quot;");
		assertThat(get(container, "/app/errors?case=committed").text())
			.isEqualTo("sent sendError=IllegalStateException sendRedirect=IllegalStateException");
		// A location is sent as it was given, with no body (Jetty makes a relative
		// one absolute to the path).
		assertThat(told(get(container, "/app/errors?case=redirect&to=/elsewhere?a=b")))
			.isEqualTo("302 Content-Length: 0 Location: /elsewhere?a=b []");
		assertThat(get(container, "/app/errors?case=redirect&to=../up").field("Location"))
			.isEqualTo(measured(container, "../up", "../up", "/up"));
		assertThat(get(container, "/app/errors?case=redirect&to=http://other.example/x").field("Location"))
			.isEqualTo("http://other.example/x");
		assertThat(told(get(container, "/app/errors?case=redirect-status")))
			.isEqualTo("303 Content-Length: 0 Location: /see/other []");
		assertThat(told(get(container, "/app/errors?case=redirect-kept")))
			.isEqualTo("307 Content-Length: 4 Location: /kept [kept]");
		// An error page (Container.ERROR_STATUS, ERROR_BY_TYPE) answers by an ERROR
		// dispatch of GET, through the filters of that dispatch, with the status asked
		// for unless it sets one, the fields set before, and the error as attributes;
		// sendError leaves the response committed. A POST's page is asked by GET.
		String paged = "<ERROR>ERROR GET /app/failed/status /failed/status committed=true status_code=410 "
				+ "message=gone away exception_type=null exception=null request_uri=/app/errors servlet_name=errors";
		Http.Reply page = get(container, "/app/errors?case=paged");
		assertThat(told(page)).isEqualTo("299 Content-Type: "
				+ measured(container, "text/plain;charset=ISO-8859-1", "text/plain;charset=ISO-8859-1",
						"text/plain;charset=iso-8859-1")
				+ " Content-Length: 213 [" + paged + " query_string=case=paged method=GET]");
		assertThat(page.field("X-Before") + page.field("X-Page")).isEqualTo("11");
		assertThat(send(container, "POST", "/app/errors?case=paged", "").text())
			.isEqualTo(paged + " query_string=case=paged method=POST");
		// forwarded: the request's own path and query
		assertThat(get(container, "/app/errors?case=paged-forward").text())
			.isEqualTo(paged + " query_string=case=paged-forward method=GET");
		// with no message: empty (Jetty: the reason)
		assertThat(get(container, "/app/errors?case=paged-bare").text())
			.contains("status_code=410 message=" + measured(container, "", "", "Gone") + " exception_type=null");
		// By the nearest class of the exception, then of a ServletException's cause,
		// which is what the attributes tell; the message is the exception's (Jetty:
		// its text).
		String typed = "<ERROR>ERROR GET /app/failed/type /failed/type committed=null status_code=500 message=";
		assertThat(get(container, "/app/errors?case=refused").text())
			.startsWith(typed + measured(container, "", "", "am.ik.servlet.httpserver.Application$Refused: ")
					+ "refused exception_type=Refused exception=Refused:refused request_uri=/app/errors");
		assertThat(get(container, "/app/errors?case=refused-below").text()).contains("exception=:refused below");
		assertThat(get(container, "/app/errors?case=refused-wrapped").text())
			.startsWith(typed + measured(container, "", "", "jakarta.servlet.ServletException: ")
					+ "wrapped exception_type=Refused exception=Refused:refused inside");
		// An exception is a 500 while the response is not committed.
		for (String thrown : new String[] { "servlet-exception", "runtime-exception", "io-exception" }) {
			Http.Reply failed = get(container, "/app/errors?case=" + thrown);
			assertThat(failed.status).as(thrown).isEqualTo(500);
			assertThat(failed.field("X-Before")).isEqualTo("1");
		}
		// A servlet permanently unavailable is out of service: not found, and not asked
		// again (2.3.3.2). One unavailable for a time is a 503.
		assertThat(get(container, "/app/unavailable").field("X-Before")).isEqualTo("1");
		Http.Reply unavailable = get(container, "/app/unavailable");
		assertThat(unavailable.status).isEqualTo(404);
		assertThat(unavailable.field("X-Before")).isNull();
		Http.Reply busy = get(container, "/app/busy");
		assertThat(busy.status).isEqualTo(503);
		// Once committed, a failure cuts the chunks, so the client sees the body is not
		// whole (Jetty ends it as it is, by closing the connection).
		String afterCommit;
		try {
			afterCommit = told(get(container, "/app/errors?case=after-commit"));
		}
		catch (IOException ex) {
			afterCommit = ex.getMessage();
		}
		assertThat(afterCommit).isEqualTo(measured(container, "the chunks end without the last one",
				"the chunks end without the last one", "200 [sent]"));
		if (container == Container.HTTPSERVER) {
			assertThat(busy.field("Retry-After")).isEqualTo("5");
			assertThat(error.text()).isEqualTo("<!doctype html><html><head><title>404 Not Found</title></head>"
					+ "<body><h1>404 Not Found</h1></body></html>");
		}
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void aDispatchReachesAnotherServlet(Container container) throws Exception {
		// A forward: the buffer is dropped, the fields stay, the path is the target's,
		// the target's query comes first, the attributes tell the first path.
		Http.Reply forward = get(container, "/app/dispatch?case=forward&to=/target/y?added=1%2B&added=2");
		assertThat(forward.status).isEqualTo(203);
		assertThat(forward.field("X-Kept")).isEqualTo("1");
		assertThat(forward.text()).isEqualTo("target FORWARD uri=/app/target/y servlet=/target info=/y query=added=1+"
				+ " mapping=PATH[y][/target/*][target] added=[1 , 2] case=forward wrapped=null handed=over"
				+ " forward=/app/dispatch,/app,/dispatch,null,case=forward&to=/target/y?added=1%2B&added=2,"
				+ "EXACT[dispatch][/dispatch][dispatch] include=null,null,null,null,null,null");
		assertThat(get(container, "/app/dispatch?case=forward&to=/dispatch?case=forward%26to=/target/twice").text())
			.startsWith("target FORWARD uri=/app/target/twice ")
			.contains(
					" forward=/app/dispatch,/app,/dispatch,null,case=forward&to=/dispatch?case=forward%26to=/target/twice,");
		assertThat(get(container, "/app/dispatch/deeper/x?case=forward&to=../../target/sibling").text())
			.contains(" servlet=/target info=/sibling ")
			.contains(" forward=/app/dispatch/deeper/x,/app,/dispatch/deeper,/x,");
		assertThat(get(container, "/app/dispatch?case=forward&to=/a.ext").text())
			.isEqualTo("uri=/app/a.ext context=/app servlet=/a.ext info=null query=case=forward&to=/a.ext"
					+ " mapping=EXTENSION[a][*.ext][paths]");
		assertThat(get(container, "/app/dispatch?case=forward-committed").text())
			.isEqualTo("sent forward=IllegalStateException");
		// An include: the path stays the including servlet's, the attributes tell the
		// target's, and the target changes neither the status nor a field.
		Http.Reply include = get(container, "/app/dispatch?case=include&to=/target/included?added=inc");
		assertThat(include.status).isEqualTo(200);
		assertThat(include.field("X-Target")).isNull();
		assertThat(include.text()).isEqualTo("[before]<included:INCLUDE>target INCLUDE uri=/app/dispatch"
				+ " servlet=/dispatch info=null query=case=include&to=/target/included?added=inc"
				+ " mapping=EXACT[dispatch][/dispatch][dispatch] added=[inc] case=include wrapped=null handed=null"
				+ " forward=null,null,null,null,null,null include=/app/target/included,/app,/target,/included,"
				+ "added=inc,PATH[included][/target/*][target]</included>[after null REQUEST null]");
		// By name: nothing of the path changes and no attribute is set.
		assertThat(told(get(container, "/app/dispatch?case=named"))).startsWith("203 ")
			.contains("[target FORWARD uri=/app/dispatch servlet=/dispatch info=null query=case=named ")
			.endsWith(" forward=null,null,null,null,null,null include=null,null,null,null,null,null]");
		assertThat(get(container, "/app/dispatch?case=named-include").text())
			.startsWith("[before]target INCLUDE uri=/app/dispatch ")
			.endsWith(" include=null,null,null,null,null,null[after]");
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void filtersRunInTheOrderOfTheirMappings(Container container) throws Exception {
		// By URL pattern first -- one mapped "not after" before the ones mapped "after"
		// -- then by servlet name.
		Http.Reply filtered = get(container, "/app/filtered/x");
		assertThat(filtered.field("X-Filters")).isEqualTo("url|after|named");
		assertThat(filtered.text()).startsWith("<url:REQUEST><after:REQUEST><named:REQUEST>target REQUEST ")
			.endsWith("</named></after></url>");
		// A filter hands on a request of its own.
		assertThat(get(container, "/app/filtered/wrapped").text())
			.startsWith("<url:REQUEST><wrapping:REQUEST><after:REQUEST><named:REQUEST>")
			.contains(" wrapped=yes ");
		// A filter that does not go on ends the chain.
		assertThat(get(container, "/app/filtered/x?stop=named").text())
			.isEqualTo("<url:REQUEST><after:REQUEST>stopped by named</after></url>");
		// A pattern is matched as a servlet's is: by whole segments.
		assertThat(get(container, "/app/filtered").field("X-Filters")).isEqualTo("url|after|named");
		assertThat(get(container, "/app/filteredx").field("X-Filters")).isNull();
		assertThat(get(container, "/app/target/x").field("X-Filters")).isEqualTo("named");
		// A filter of forwards only is in the chain of a forward only.
		assertThat(get(container, "/app/target/forwarded").field("X-Filters")).isEqualTo("named");
		assertThat(get(container, "/app/dispatch?case=forward&to=/target/forwarded").text())
			.startsWith("<forwarded:FORWARD>target FORWARD ");
	}

	/** The id a {@code Set-Cookie} field gives the session cookie. */
	private static String sessionId(Http.Reply reply) {
		String cookie = reply.field("Set-Cookie");
		return cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void aSessionIsTrackedByItsCookieOrItsUrl(Container container) throws Exception {
		// Made: the cookie's attributes, the interval, the id, and the default tracking
		// modes (here the cookie alone, so encodeURL leaves a URL as it is). Jetty's
		// embedded context has no interval and an id of its own form.
		Http.Reply created = get(container, "/app/session?case=create");
		String id = sessionId(created);
		assertThat(created.field("Set-Cookie").replace(id, "ID"))
			.isEqualTo(measured(container, "JSESSIONID=ID; HttpOnly; Path=/app", "JSESSIONID=ID; Path=/app; HttpOnly",
					"JSESSIONID=ID; Path=/app"));
		assertThat(created.text()).isEqualTo(measured(container,
				"new=true max=1800 requested=null valid=false cookie=false url=false encoded=x?y#z"
						+ " config=null,null,true,false,-1,{HttpOnly=} modes=[COOKIE],[COOKIE] id=hex",
				"new=true max=1800 requested=null valid=false cookie=false url=false encoded=x;jsessionid=ID?y#z"
						+ " config=null,null,false,false,-1,{} modes=[COOKIE, URL],[COOKIE, URL] id=hex",
				"new=true max=-1 requested=null valid=false cookie=false url=false encoded=x;jsessionid=ID.node0?y#z"
						+ " config=JSESSIONID,/app,false,false,-1,{comment=null, domain=null, httponly=false,"
						+ " max-age=-1, name=JSESSIONID, path=/app, secure=false}"
						+ " modes=[COOKIE, URL],[COOKIE, URL] id=own"));
		// Found by the cookie; Jetty's cookie is the id and a suffix naming the node, its
		// requested id the cookie's value.
		String requested = measured(container, "this", "this", id);
		assertThat(get(container, "/app/session?case=read", "Cookie: JSESSIONID=" + id).text())
			.isEqualTo("found=true requested=" + requested + " valid=true cookie=true url=false new=false a=two"
					+ " encoded=x?y#z same=true times=true");
		// A new id: the session and its attributes stay. The id the client sent is no
		// longer valid; Tomcat answers the new one as the requested id.
		Http.Reply changed = get(container, "/app/session?case=change", "Cookie: JSESSIONID=" + id);
		String changedId = sessionId(changed);
		assertThat(changedId).isNotEqualTo(id);
		assertThat(changed.text()).isEqualTo("changed=true now=true " + measured(container,
				"requested=before valid=false", "requested=this valid=true", "requested=before.node0 valid=false")
				+ " cookie=true url=false a=two");
		assertThat(get(container, "/app/session?case=read", "Cookie: JSESSIONID=" + id).text())
			.startsWith("found=false requested=" + id + " valid=false cookie=true");
		// An id no session has is requested and invalid; a session made then has an id
		// of the container's, not the client's.
		Http.Reply unknown = get(container, "/app/session?case=create", "Cookie: JSESSIONID=nonsense");
		assertThat(unknown.text()).contains(" requested=nonsense valid=false cookie=true url=false ");
		assertThat(sessionId(unknown)).isNotEqualTo("nonsense");
		// Invalidated: the session is gone and refuses to be used; the next is new.
		assertThat(get(container, "/app/session?case=invalidate", "Cookie: JSESSIONID=" + changedId).text())
			.isEqualTo("after=null requested=" + measured(container, "before", "before", "before.node0")
					+ " valid=false cookie=true url=false getAttribute=IllegalStateException"
					+ " invalidate=IllegalStateException made=true,true");
		// An accessor (6.1) reaches the session outside the request until it is
		// invalidated.
		String accessed = sessionId(get(container, "/app/session?case=create"));
		assertThat(get(container, "/app/session?case=accessor", "Cookie: JSESSIONID=" + accessed).text())
			.isEqualTo("b=by accessor invalidated=IllegalStateException");
		assertThat(get(container, "/app/session?case=committed").text())
			.isEqualTo("sent getSession=IllegalStateException");
		// The listeners and the bound values: a destroyed session's attributes are still
		// there for its listeners. A replaced attribute's event holds the old value
		// (Tomcat: the new); the old value is unbound after the new is bound (Jetty:
		// before).
		String made = "created true, bound one, added a=one, "
				+ measured(container, "bound two, unbound one, replaced a=one",
						"bound two, unbound one, replaced a=two", "unbound one, bound two, replaced a=one")
				+ " by two, ";
		String invalidated = "destroyed a=two, unbound two, removed a=two, ";
		assertThat(get(container, "/app/session?case=events").text())
			.isEqualTo("[" + made + "id changed true, " + made + invalidated + "created true, " + made
					+ "added b=by accessor, " + invalidated + "removed b=by accessor]");
		// Tracked by URLs alone: no cookie, and a URL of the application carries the id.
		int root = ROOT.get(container).port;
		Http.Reply byUrl = Http.get(root, "/session?case=create&show");
		assertThat(byUrl.field("Set-Cookie")).isNull();
		assertThat(byUrl.text()).contains(" encoded=x;jsessionid=ID" + measured(container, "", "", ".node0") + "?y#z ");
		String urlId = byUrl.text().substring(byUrl.text().lastIndexOf(' ') + 1)
				+ measured(container, "", "", ".node0");
		String byPath = measured(container, "this", "this", urlId);
		assertThat(Http.get(root, "/session;jsessionid=" + urlId + "?case=read").text())
			.isEqualTo("found=true requested=" + byPath
					+ " valid=true cookie=false url=true new=false a=two encoded=x;jsessionid="
					+ measured(container, "ID", "ID", "ID.node0") + "?y#z same=true times=true");
		// The cookie is not read where sessions are not tracked by cookies.
		assertThat(Http.get(root, "/session;jsessionid=" + urlId + "?case=read", "Cookie: JSESSIONID=nonsense").text())
			.startsWith("found=true requested=" + byPath + " valid=true cookie=false url=true ");
	}

	/** What {@link #told} makes of a response sent whole with a body of ASCII. */
	private static String whole(String body) {
		return "200 Content-Length: " + body.length() + " [" + body + "]";
	}

	/** The id an asynchronous request of a test is asked with. */
	private static String id(Container container, String query) {
		return container + "-" + query.replace('&', '.').replace('=', '-');
	}

	/**
	 * What an asynchronous request asked with an id answered, and what it did once ended.
	 */
	private static List<String> asked(Container container, String query) throws Exception {
		String id = id(container, query);
		Http.Reply reply;
		try {
			reply = get(container, "/app/async/x?" + query + "&id=" + id);
		}
		catch (IOException ex) {
			// the connection closed with no reply
			return List.of("no reply", String.valueOf(APPLICATIONS.get(container).trace(id).ended()));
		}
		return List.of(told(reply), String.valueOf(APPLICATIONS.get(container).trace(id).ended()));
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void anAsynchronousCycleEndsByCompletionDispatchTimeoutOrError(Container container) throws Exception {
		// Completed by a task of start(): the response waits for complete(), and the
		// request is destroyed once complete (Jetty: at the end of each dispatch). A
		// listener added with no objects is handed none (the javadoc), where the two
		// others hand some.
		assertThat(
				asked(container, "case=start&listened"))
			.containsExactly("200 Content-Length: 39 [by a task, committed=false started=true]",
					"[initialized, started=true original=true timeout=30000 same=true again=IllegalStateException, "
							+ measured(container, "complete supplied=false, destroyed",
									"complete supplied=true, destroyed", "destroyed, complete supplied=true")
							+ "]");
		// A dispatch from another thread goes to the request's path, with the filters of
		// asynchronous dispatches; the attributes tell where the request went first.
		String id = id(container, "case=dispatch");
		assertThat(asked(container, "case=dispatch"))
			.containsExactly(whole("ASYNC uri=/app/async/x servlet=/async info=/x query=case=dispatch&id=" + id
					+ " async=/app/async/x,/app,/async,/x,case=dispatch&id=" + id
					+ ",PATH[x][/async/*][async] filtered=REQUEST,ASYNC started=false x=null"), "[complete]");
		// A dispatch's target may start a cycle of its own.
		assertThat(asked(container, "case=restart")).containsExactly("200 Content-Length: 9 [restarted]",
				"[start, complete]");
		// One asked during the dispatch that started the cycle runs once it returned:
		// what was written meanwhile is kept, and a second dispatch or complete() is
		// refused.
		id = id(container, "case=dispatch-inside");
		assertThat(asked(container, "case=dispatch-inside").get(0)).endsWith(" [kept|ASYNC uri=/app/async/there"
				+ " servlet=/async info=/there query=case=print&x=1 async=/app/async/x,/app,/async,/x,"
				+ "case=dispatch-inside&id=" + id
				+ ",PATH[x][/async/*][async] filtered=REQUEST,ASYNC started=false x=1]");
		assertThat(APPLICATIONS.get(container).trace(id).ended())
			.containsExactly("started=true again=IllegalStateException complete=IllegalStateException", "complete");
		// Completed by a thread of the application's own.
		Application.Trace held = APPLICATIONS.get(container).trace(container + "-held");
		ExecutorService clients = Executors.newSingleThreadExecutor();
		try {
			Future<Http.Reply> reply = clients
				.submit(() -> get(container, "/app/async/x?case=held&id=" + container + "-held"));
			assertThat(held.held.await(20, TimeUnit.SECONDS)).isTrue();
			held.context.getResponse().getWriter().print("completed by another thread");
			held.context.complete();
			assertThat(told(reply.get(30, TimeUnit.SECONDS)))
				.isEqualTo("200 Content-Length: 27 [completed by another thread]");
			assertThat(held.ended()).containsExactly("complete");
		}
		finally {
			clients.shutdownNow();
		}
		// A timeout: the listeners, which may answer; else a 500, the fields set before
		// kept.
		assertThat(asked(container, "case=timeout&answer"))
			.containsExactly("200 Content-Length: 23 [answered at the timeout]", "[timeout, complete]");
		List<String> timedOut = asked(container, "case=timeout");
		assertThat(timedOut.get(0)).startsWith("500 ");
		assertThat(timedOut.get(1)).isEqualTo("[timeout, complete]");
		if (container == Container.HTTPSERVER) {
			// Once the dispatch that started the cycle returned, a timeout or a listener
			// is refused (the javadoc). Asked by hand of the two others, 200 ms after the
			// dispatch returned: both take them.
			assertThat(asked(container, "case=timeout&answer&late").get(1))
				.isEqualTo("[timeout, late IllegalStateException,IllegalStateException, complete]");
		}
		AsyncContext ended = APPLICATIONS.get(container).trace(id(container, "case=timeout")).context;
		// What the application asks once the container completed the request is refused.
		assertThat(Application.thrown(ended::complete) + " " + Application.thrown(ended::dispatch) + " "
				+ Application.thrown(ended::getResponse))
			.isEqualTo("IllegalStateException IllegalStateException IllegalStateException");
		// An error while asynchronous: the listeners are told, then the answer is a 500
		// (Tomcat's keeps what the buffer holds).
		List<String> thrown = asked(container, "case=thrown");
		assertThat(thrown.get(0)).startsWith("500 ").contains(measured(container, "<h1>500", "[junk]", "500"));
		assertThat(thrown.get(1)).isEqualTo("[error thrown after startAsync, complete]");
		// A task of start() that throws ends the request at once here; the two others log
		// it and wait for the timeout.
		List<String> task = asked(container, "case=thrown-task");
		assertThat(task.get(0)).startsWith("500 ");
		assertThat(task.get(1)).isEqualTo(measured(container, "[error thrown by a task, complete]",
				"[timeout, complete]", "[timeout, complete]"));
		// A dispatch that throws: Jetty tells no listener.
		List<String> dispatchThrew = asked(container, "case=thrown-dispatch");
		assertThat(dispatchThrew.get(0)).startsWith("500 ");
		assertThat(dispatchThrew.get(1)).isEqualTo(measured(container, "[error thrown by the dispatch, complete]",
				"[error thrown by the dispatch, complete]", "[complete]"));
		// Refused: a read listener of a request that is not asynchronous, its context, a
		// second start in one dispatch, a start where a servlet does not support it.
		assertThat(asked(container, "case=refusals").get(0)).endsWith(
				" [readListener=IllegalStateException getAsyncContext=IllegalStateException twice=IllegalStateException]");
		assertThat(get(container, "/app/sync").text())
			.isEqualTo("supported=false startAsync=IllegalStateException started=false");
		// A cycle started in a forward's target: startAsync() dispatches back to the
		// request's own path, the pair to the target's; the attributes tell the request's
		// first path (Jetty: the forward's; Tomcat's mapping is the forward's).
		id = id(container, "case=forwarded&how=plain");
		assertThat(asked(container, "case=forwarded&how=plain")).containsExactly(
				whole("ASYNC uri=/app/async/x servlet=/async info=/x query=case=forwarded&how=plain&id=" + id
						+ " async=/app/async/x,/app,/async,/x,case=forwarded&how=plain&id=" + id
						+ ",PATH[x][/async/*][async] filtered=REQUEST,ASYNC started=false x=null"),
				"[original=true, complete]");
		id = id(container, "case=forwarded&how=pair");
		assertThat(asked(container, "case=forwarded&how=pair").get(0))
			.endsWith(" [ASYNC uri=/app/async/b servlet=/async info=/b query=case=forwarded-target&how=pair async="
					+ measured(container, "/app/async/x,/app,/async,/x,case=forwarded&how=pair&id=" + id + ",PATH[x]",
							"/app/async/x,/app,/async,/x,case=forwarded&how=pair&id=" + id + ",PATH[b]",
							"/app/async/b,/app,/async,/b,case=forwarded-target&how=pair,PATH[b]")
					+ "[/async/*][async] filtered=REQUEST,ASYNC started=false x=null]");
		assertThat(APPLICATIONS.get(container).trace(id).ended()).containsExactly("original=false", "complete");
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void nonBlockingReadsAndWritesAreCalledBackOnce(Container container) throws Exception {
		// A read listener: the data, read while the stream is ready and not finished,
		// then its end. The stream is finished once the length declared is read.
		String id = container + "-read-post";
		Http.Reply echoed = send(container, "POST", "/app/async/x?case=read&id=" + id, "hello, world");
		assertThat(told(echoed)).isEqualTo("200 Content-Length: 17 [echo hello, world]");
		String nulled = "null="
				+ measured(container, "NullPointerException", "NullPointerException", "IllegalStateException");
		// Jetty's is finished once a read found the end.
		assertThat(String.valueOf(APPLICATIONS.get(container).trace(id).ended()))
			.isEqualTo("[ready=true finished=false, again=IllegalStateException " + nulled
					+ ", read 4 finished=false, read 4 finished=false, " + measured(container, "read 4 finished=true",
							"read 4 finished=true", "read 4 finished=false, read -1 finished=true")
					+ ", all read, complete]");
		// No body: its end alone. A blocking read is refused once a read listener is set
		// (Jetty: read).
		assertThat(asked(container, "case=read")).containsExactly("200 Content-Length: 5 [echo ]",
				"[" + measured(container, "ready=true finished=true", "ready=true finished=true",
						"ready=false finished=false") + ", again=IllegalStateException " + nulled + ", blocking="
						+ measured(container, "IllegalStateException", "IllegalStateException", "ok")
						+ ", all read, complete]");
		// A listener that throws: its onError, then the error's course (Tomcat closes the
		// connection with no reply; Jetty waits for the timeout).
		id = container + "-read-thrown";
		String reply;
		try {
			reply = told(send(container, "POST", "/app/async/x?case=read&throw&id=" + id, "hello, world"));
		}
		catch (IOException ex) {
			reply = "no reply";
		}
		assertThat(reply).startsWith(measured(container, "500 ", "no reply", "500 "));
		assertThat(APPLICATIONS.get(container).trace(id).ended()).containsExactly("ready=true finished=false",
				"again=IllegalStateException " + nulled, "read error thrown by onDataAvailable",
				measured(container, "error thrown by onDataAvailable", "error thrown by onDataAvailable", "timeout"),
				"complete");
		// A write listener: called once, the stream always ready.
		assertThat(asked(container, "case=write")).containsExactly("200 Content-Length: 7 [written]",
				"[again=IllegalStateException, writable ready=true, complete]");
	}

	private static final String MULTIPART = "multipart/form-data; boundary=XyZ";

	/** The octets of a text in UTF-8, but for a character after U+0001, which is one. */
	private static byte[] octets(String text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\u0001') {
				out.write(text.charAt(++i));
			}
			else {
				out.writeBytes(String.valueOf(text.charAt(i)).getBytes(StandardCharsets.UTF_8));
			}
		}
		return out.toByteArray();
	}

	/** A part of the boundary {@code XyZ}: its fields, then its body. */
	private static String part(String disposition, String... fieldsThenBody) {
		StringBuilder part = new StringBuilder("--XyZ\r\n");
		if (disposition != null) {
			part.append("Content-Disposition: form-data; ").append(disposition).append("\r\n");
		}
		for (int i = 0; i < fieldsThenBody.length - 1; i++) {
			part.append(fieldsThenBody[i]).append("\r\n");
		}
		return part.append("\r\n").append(fieldsThenBody[fieldsThenBody.length - 1]).append("\r\n").toString();
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void aMultipartBodyIsReadAsPartsWithinItsLimits(Container container) throws Exception {
		String end = "--XyZ--\r\n";
		String refused = "getParts=IOException getPart=IOException";
		String tooLarge = "getParts=IllegalStateException getPart=IllegalStateException";
		String jettyRefused = "getParts=ServletException(IllegalStateException) getPart=ServletException(IllegalStateException)";
		String jettyNoName = "getParts=ServletException(IllegalStateException) getPart=NullPointerException";
		String fieldA = "a file=null type=null size=%d fields=[content-disposition] [%s]\n";
		String unnamed = "files=0\ngetPart=null\nparameters=";
		// The name, the method and target, the Content-Type (null: the boundary XyZ), the
		// X-Mode, the body; what each container answered (null: what this one did).
		String[][] asked = {
				// The preamble and the epilogue are dropped; a part above the threshold
				// of
				// 16 octets is in a file; a part with a file name is no parameter. Tomcat
				// reads a field as ISO-8859-1 and puts the query's parameters after.
				{ "plain", "POST /app/parts?q=0", null, null,
						"preamble\r\n" + part("name=\"a\"", "1") + part("name=\"a\"", "あ")
								+ part("name=\"file\"; filename=\"a;b.txt\"", "Content-Type: text/plain", "X-Extra: 1",
										"a file of more than 16 octets")
								+ part("name=\"empty\"; filename=\"\"", "Content-Type: application/octet-stream", "")
								+ end + "epilogue",
						"files=1\n" + fieldA.formatted(1, "1") + fieldA.formatted(3, "あ")
								+ "file file=a;b.txt type=text/plain size=29 fields=[content-disposition, content-type, x-extra] [a file of more than 16 octets]\n"
								+ "empty file= type=application/octet-stream size=0 fields=[content-disposition, content-type] []\n"
								+ "getPart=a\nparameters=q[0]a[1, あ]",
						"files=1\n" + fieldA.formatted(1, "1") + fieldA.formatted(3, "あ")
								+ "file file=a;b.txt type=text/plain size=29 fields=[content-disposition, content-type, x-extra] [a file of more than 16 octets]\n"
								+ "empty file= type=application/octet-stream size=0 fields=[content-disposition, content-type] []\n"
								+ "getPart=a\nparameters=a[1, ã\u0081\u0082]q[0]",
						null },
				// Transport padding after a delimiter (RFC 2046): Tomcat finds no part.
				{ "padding", "POST /app/parts", null, null,
						"--XyZ \t\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--XyZ-- \r\n",
						"files=0\n" + fieldA.formatted(1, "1") + "getPart=a\nparameters=a[1]", unnamed, null },
				// A quoted file name: a backslash escapes a quote and is otherwise kept
				// (Tomcat drops it); filename* (RFC 8187) wins; a field's own charset
				// (Tomcat: ISO-8859-1).
				{ "names", "POST /app/parts", null, null,
						part("name=\"q\"; filename=\"a\\\"b.txt\"", "1")
								+ part("name=\"star\"; filename=\"plain.txt\"; filename*=UTF-8''%E2%82%AC.txt", "2")
								+ part("name=\"path\"; filename=\"C:\\dir\\x.txt\"", "3")
								+ part("name=\"sj\"", "Content-Type: text/plain; charset=Shift_JIS",
										"\u0001\u0082\u0001\u00a0")
								+ part("name=b", "unquoted") + end,
						"files=0\nq file=a\"b.txt type=null size=1 fields=[content-disposition] [1]\n"
								+ "star file=€.txt type=null size=1 fields=[content-disposition] [2]\n"
								+ "path file=C:\\dir\\x.txt type=null size=1 fields=[content-disposition] [3]\n"
								+ "sj file=null type=text/plain; charset=Shift_JIS size=2 fields=[content-disposition, content-type] [\ufffd\ufffd]\n"
								+ "b file=null type=null size=8 fields=[content-disposition] [unquoted]\n"
								+ "getPart=null\nparameters=sj[あ]b[unquoted]",
						"files=0\nq file=a\"b.txt type=null size=1 fields=[content-disposition] [1]\n"
								+ "star file=€.txt type=null size=1 fields=[content-disposition] [2]\n"
								+ "path file=C:dirx.txt type=null size=1 fields=[content-disposition] [3]\n"
								+ "sj file=null type=text/plain; charset=Shift_JIS size=2 fields=[content-disposition, content-type] [\ufffd\ufffd]\n"
								+ "b file=null type=null size=8 fields=[content-disposition] [unquoted]\n"
								+ "getPart=null\nparameters=sj[\u0082\u00a0]b[unquoted]",
						null },
				// The fields of a part are read in the request's charset, the fields'
				// values
				// in the one _charset_ names (RFC 7578, 4.6): Tomcat reads both in the
				// request's, Jetty both in UTF-8 but for _charset_.
				{ "charsets", "POST /app/parts", MULTIPART + "; charset=ISO-8859-1", null,
						part("name=\"_charset_\"", "Shift_JIS") + part("name=\"a\"", "\u0001\u0082\u0001\u00a0")
								+ part("name=\"f\"; filename=\"é.txt\"", "x") + end,
						"files=0\n_charset_ file=null type=null size=9 fields=[content-disposition] [Shift_JIS]\n"
								+ fieldA.formatted(2, "\ufffd\ufffd")
								+ "f file=Ã©.txt type=null size=1 fields=[content-disposition] [x]\n"
								+ "getPart=a\nparameters=_charset_[Shift_JIS]a[あ]",
						"files=0\n_charset_ file=null type=null size=9 fields=[content-disposition] [Shift_JIS]\n"
								+ fieldA.formatted(2, "\ufffd\ufffd")
								+ "f file=Ã©.txt type=null size=1 fields=[content-disposition] [x]\n"
								+ "getPart=a\nparameters=_charset_[Shift_JIS]a[\u0082\u00a0]",
						"files=0\n_charset_ file=null type=null size=9 fields=[content-disposition] [Shift_JIS]\n"
								+ fieldA.formatted(2, "\ufffd\ufffd")
								+ "f file=é.txt type=null size=1 fields=[content-disposition] [x]\n"
								+ "getPart=a\nparameters=_charset_[Shift_JIS]a[あ]" },
				{ "empty", "POST /app/parts", null, null, end, unnamed, null, null },
				// What RFC 7578 and 2046 do not allow is refused, every part with it:
				// Tomcat leaves out a part with no name, ends a body at a delimiter, and
				// takes a delimiter followed by other text; Jetty takes lines ended by
				// LF.
				{ "no-disposition", "POST /app/parts", null, null,
						part(null, "Content-Type: text/plain", "anonymous") + part("name=\"a\"", "named") + end,
						refused, "files=0\n" + fieldA.formatted(5, "named") + "getPart=a\nparameters=a[named]",
						jettyNoName },
				{ "no-name", "POST /app/parts", null, null, part("filename=\"x.txt\"", "no name") + end, refused,
						unnamed, jettyNoName },
				{ "no-end", "POST /app/parts", null, null, part("name=\"a\"", "cut").replace("cut\r\n", "cut"), refused,
						null, jettyRefused },
				{ "no-end-after-a-delimiter", "POST /app/parts", null, null, part("name=\"a\"", "cut") + "--XyZ",
						refused, "files=0\n" + fieldA.formatted(3, "cut") + "getPart=a\nparameters=a[cut]",
						jettyRefused },
				{ "lines-ended-by-lf", "POST /app/parts", null, null,
						"--XyZ\nContent-Disposition: form-data; name=\"a\"\n\nlf\n--XyZ--\n", refused, null,
						"files=0\n" + fieldA.formatted(2, "lf") + "getPart=a\nparameters=a[lf]" },
				{ "a-delimiter-followed-by-text", "POST /app/parts", null, null,
						part("name=\"a\"", "x\r\n--XyZa y") + end, refused,
						"files=0\n" + fieldA.formatted(1, "x") + "getPart=a\nparameters=a[x]", jettyRefused },
				{ "no-boundary", "POST /app/parts", "multipart/form-data", null, end, refused, null, jettyRefused },
				// A folded field (RFC 5322) is unfolded; Jetty refuses it.
				{ "folded", "POST /app/parts", null, null,
						"--XyZ\r\nContent-Disposition: form-data;\r\n name=\"a\"\r\n\r\nfolded\r\n" + end,
						"files=0\n" + fieldA.formatted(6, "folded") + "getPart=a\nparameters=a[folded]", null,
						jettyRefused },
				// The limits, of a part (64 octets) and of the body (1024): an
				// IllegalStateException, which Jetty wraps.
				{ "a-part-too-large", "POST /app/parts", null, null,
						part("name=\"f\"; filename=\"big\"", "x".repeat(65)) + end, tooLarge, null, jettyRefused },
				{ "a-body-too-large", "POST /app/parts", null, null,
						part("name=\"a\"", "x".repeat(60)).repeat(16) + end, tooLarge, null, jettyRefused },
				{ "a-field-too-large", "POST /app/parts", null, "parameters", part("name=\"a\"", "x".repeat(65)) + end,
						"parameters=IllegalStateException\n" + tooLarge, null,
						"parameters=IllegalStateException\n" + jettyRefused },
				// Without a multipart configuration the parts are not read: Tomcat
				// answers
				// none.
				{ "unconfigured", "POST /app/unconfigured?q=0", null, "parameters", part("name=\"a\"", "1") + end,
						"parameters=q[0]\n" + tooLarge, "parameters=q[0]\nfiles=0\ngetPart=null",
						"parameters=q[0]\n" + jettyRefused },
				// The parameters first, of any method.
				{ "parameters-first", "PUT /app/parts?q=0", null, "parameters",
						part("name=\"a\"", "1")
								+ part("name=\"f\"; filename=\"f.txt\"", "Content-Type: text/plain", "file") + end,
						"parameters=q[0]a[1]\nfiles=0\n" + fieldA.formatted(1, "1")
								+ "f file=f.txt type=text/plain size=4 fields=[content-disposition, content-type] [file]\ngetPart=a",
						null, null },
				// A part written to a relative name is in the directory, and read from
				// there
				// (Tomcat moves the file and can no longer read the part).
				{ "write", "POST /app/parts", null, "write", part("name=\"small\"; filename=\"s.txt\"", "small")
						+ part("name=\"large\"; filename=\"l.txt\"", "a part of more than sixteen octets") + end,
						"files=1\nsmall file=s.txt type=null size=5 fields=[content-disposition] [small]\n"
								+ "written small, then small\n"
								+ "large file=l.txt type=null size=34 fields=[content-disposition] [a part of more than sixteen octets]\n"
								+ "written a part of more than sixteen octets, then a part of more than sixteen octets\n"
								+ "getPart=null\nparameters=",
						"files=1\nsmall file=s.txt type=null size=5 fields=[content-disposition] [small]\n"
								+ "written small, then small\n"
								+ "large file=l.txt type=null size=34 fields=[content-disposition] [a part of more than sixteen octets]\n"
								+ "written a part of more than sixteen octets, then IOException\n"
								+ "getPart=null\nparameters=",
						null },
				// A body the servlet read: Tomcat answers no part.
				{ "stream-first", "POST /app/parts", null, "stream", part("name=\"a\"", "1") + end,
						"read=45\n" + tooLarge, "read=45\n" + unnamed, "read=45\n" + jettyRefused } };
		int port = APPLICATION.get(container).port;
		Path uploads = APPLICATIONS.get(container).uploads;
		for (String[] row : asked) {
			String[] line = row[1].split(" ");
			String type = "Content-Type: " + (row[2] == null ? MULTIPART : row[2]);
			Http.Reply reply = row[3] == null ? Http.send(port, line[0], line[1], octets(row[4]), type)
					: Http.send(port, line[0], line[1], octets(row[4]), type, "X-Mode: " + row[3]);
			assertThat(reply.text()).as(row[0])
				.isEqualTo(measured(container, row[5], row[6] == null ? row[5] : row[6],
						row[7] == null ? row[5] : row[7]));
			// The files of the parts are deleted once the request is complete (Jetty:
			// soon after).
			for (int tries = 0; tries < 50 && !files(uploads).isEmpty(); tries++) {
				Thread.sleep(100);
			}
			assertThat(files(uploads)).as(row[0]).isEmpty();
		}
		// A body of chunks is counted as it is read.
		String body = part("name=\"a\"", "x".repeat(60)).repeat(16) + end;
		assertThat(Http
			.raw(port,
					("POST /app/parts HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n"
							+ "Connection: close\r\nContent-Type: " + MULTIPART + "\r\n\r\n"
							+ Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1))
			.text()).isEqualTo(measured(container, tooLarge, tooLarge, jettyRefused));
	}

	private static List<Path> files(Path directory) throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.toList();
		}
	}

	/** Sun, 06 Nov 1994 08:49:37 GMT: when every file of a document root was modified. */
	private static final long MODIFIED = 784111777000L;

	private static final String LAST_MODIFIED = "Sun, 06 Nov 1994 08:49:37 GMT";

	/**
	 * Writes files, each a name and its text, with the directories above them, which a
	 * jar holds as entries of their own as the jar tool writes them.
	 */
	private static void files(Path root, String... namesThenTexts) throws IOException {
		for (int i = 0; i < namesThenTexts.length; i += 2) {
			Path file = root.resolve(namesThenTexts[i]);
			Files.createDirectories(file.getParent());
			Files.writeString(file, namesThenTexts[i + 1]);
			Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(MODIFIED));
		}
	}

	private static void jar(Path jar, String... namesThenTexts) throws IOException {
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream archive = new JarOutputStream(out)) {
			Set<String> directories = new HashSet<>();
			for (int i = 0; i < namesThenTexts.length; i += 2) {
				String name = namesThenTexts[i];
				for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
					if (directories.add(name.substring(0, slash + 1))) {
						archive.putNextEntry(new JarEntry(name.substring(0, slash + 1)));
						archive.closeEntry();
					}
				}
				archive.putNextEntry(new JarEntry(name));
				archive.write(namesThenTexts[i + 1].getBytes(StandardCharsets.UTF_8));
				archive.closeEntry();
			}
		}
	}

	/**
	 * What a resource's answer tells: the status, and below 400 the fields that frame,
	 * type and date it and its body.
	 */
	private static String served(Http.Reply reply) {
		StringBuilder told = new StringBuilder().append(reply.status);
		String[] shown = reply.status < 400 ? new String[] { "Content-Type", "Content-Length", "Content-Range",
				"Last-Modified", "Accept-Ranges", "Location", "Allow" } : new String[] { "Content-Range", "Allow" };
		for (String name : shown) {
			if (reply.field(name) != null) {
				told.append(' ').append(name).append(": ").append(reply.field(name));
			}
		}
		return reply.status < 400 ? told.append(" [").append(reply.text()).append(']').toString() : told.toString();
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void theDefaultServletServesTheResourcesOfAPath(Container container, @TempDir Path work) throws Exception {
		// The directory the containers are given is the one their getRealPath answers:
		// the temporary directory resolved, the symbolic links a macOS one is behind
		// followed.
		Path documentRoot = Files.createDirectories(work.toRealPath().resolve("documents"));
		files(documentRoot, "index.html", "<h1>home</h1>", "style.css", "body { color: red }", "notes.txt",
				"abcdefghijklmnopqrstuvwxyz", "noext", "no extension", "empty.txt", "", "a b.txt", "spaced",
				"sub/index.html", "<h1>sub</h1>", "sub/page.html", "<h1>page</h1>", "nowelcome/file.txt", "file",
				"WEB-INF/secret.txt", "secret", "META-INF/meta.txt", "meta");
		Container.Running running = container.start("/app", documentRoot, new Application()::registerResources);
		int port = running.port;
		try {
			String text = "Content-Type: "
					+ measured(container, "text/plain", "text/plain", "text/plain;charset=iso-8859-1");
			String html = "Content-Type: " + measured(container, "text/html", "text/html", "text/html;charset=utf-8");
			String dated = " Last-Modified: " + LAST_MODIFIED + " Accept-Ranges: bytes";
			// A file: its length, its media type and its time (Tomcat adds an entity tag
			// of
			// them); a filter of every path runs first.
			Http.Reply style = Http.get(port, "/app/style.css");
			assertThat(served(style))
				.isEqualTo("200 Content-Type: text/css Content-Length: 19" + dated + " [body { color: red }]");
			assertThat(style.field("ETag")).isEqualTo(measured(container, null, "W/\"19-" + MODIFIED + "\"", null));
			assertThat(style.field("X-Filtered")).isEqualTo("yes");
			// A directory by its welcome file; asked without its slash, a redirect.
			String[][] asked = {
					{ "GET /app/notes.txt",
							"200 " + text + " Content-Length: 26" + dated + " [abcdefghijklmnopqrstuvwxyz]" },
					{ "GET /app/", "200 " + html + " Content-Length: 13" + dated + " [<h1>home</h1>]" },
					{ "GET /app/sub/", "200 " + html + " Content-Length: 12" + dated + " [<h1>sub</h1>]" },
					{ "GET /app/sub?q=1", "302 Content-Length: 0 Location: /app/sub/?q=1 []" },
					{ "GET /app/nowelcome", "302 Content-Length: 0 Location: /app/nowelcome/ []" },
					{ "GET /app/noext", "200 Content-Length: 12" + dated + " [no extension]" },
					{ "GET /app/empty.txt", "200 " + text + " Content-Length: 0" + dated + " []" },
					{ "GET /app/a%20b.txt", "200 " + text + " Content-Length: 6" + dated + " [spaced]" },
					{ "HEAD /app/style.css", "200 Content-Type: text/css Content-Length: 19" + dated + " []" },
					{ "GET /app/missing.txt", "404" }, { "GET /app/index.html/", "404" },
					// A directory with no welcome file: Jetty forbids its listing.
					{ "GET /app/nowelcome/", measured(container, "404", "404", "403") },
					// Not served, as the specification says (10.5, 10.6); Jetty's context
					// that
					// is no web application serves them.
					{ "GET /app/WEB-INF/secret.txt",
							measured(container, "404", "404",
									"200 " + text + " Content-Length: 6" + dated + " [secret]") },
					{ "GET /app/META-INF/meta.txt",
							measured(container, "404", "404",
									"200 " + text + " Content-Length: 4" + dated + " [meta]") },
					// What the default servlet takes: GET and HEAD here and in Jetty,
					// POST too
					// in Tomcat, which serves it.
					{ "POST /app/style.css",
							measured(container, "405 Allow: GET, HEAD, OPTIONS",
									"200 Content-Type: text/css Content-Length: 19" + dated + " [body { color: red }]",
									"405") },
					{ "PUT /app/style.css",
							measured(container, "405 Allow: GET, HEAD, OPTIONS", "405 Allow: OPTIONS, GET, HEAD, POST",
									"405") },
					{ "OPTIONS /app/style.css",
							measured(container, "200 Content-Length: 0 Allow: GET, HEAD, OPTIONS []",
									"200 Content-Length: 0 Allow: OPTIONS, GET, HEAD, POST []",
									"200 Content-Length: 0 Allow: GET, HEAD, OPTIONS []") } };
			for (String[] row : asked) {
				String[] line = row[0].split(" ");
				assertThat(served(Http.send(port, line[0], line[1], null))).as(row[0]).isEqualTo(row[1]);
			}
			// A request conditional on the time (RFC 9110, 13). A resource here has no
			// entity tag, which none matches; Jetty reads no entity tag.
			String[][] conditional = { { "If-Modified-Since: " + LAST_MODIFIED, "304", "304", "304" },
					{ "If-Modified-Since: Sat, 05 Nov 1994 08:49:37 GMT", "200", "200", "200" },
					{ "If-Modified-Since: yesterday", "200", "200", "200" },
					{ "If-None-Match: *", "304", "304", "200" },
					{ "If-None-Match: \"other\"|If-Modified-Since: " + LAST_MODIFIED, "200", "200", "200" },
					{ "If-Unmodified-Since: Sat, 05 Nov 1994 08:49:37 GMT", "412", "412", "412" },
					{ "If-Match: \"other\"", "412", "412", "200" }, { "If-Match: *", "200", "200", "200" },
					// A range of octets (14): one; several, answered whole here, with the
					// parts
					// of a multipart/byteranges by the two others; another unit.
					{ "Range: bytes=0-4", "206 bytes 0-4/26 [abcde]", "206 bytes 0-4/26 [abcde]",
							"206 bytes 0-4/26 [abcde]" },
					{ "Range: bytes=-3", "206 bytes 23-25/26 [xyz]", "206 bytes 23-25/26 [xyz]",
							"206 bytes 23-25/26 [xyz]" },
					{ "Range: bytes=20-100", "206 bytes 20-25/26 [uvwxyz]", "206 bytes 20-25/26 [uvwxyz]",
							"206 bytes 20-25/26 [uvwxyz]" },
					{ "Range: bytes=100-200", "416 bytes */26", "416 bytes */26", "416 bytes */26" },
					{ "Range: bytes=4-2", "416 bytes */26", "416 bytes */26", "416 bytes */26" },
					{ "Range: bytes=0-0,5-6", "200", "206 multipart/byteranges", "206 multipart/byteranges" },
					{ "Range: items=0-1", "200", "200", "416 bytes */26" },
					{ "Range: bytes=0-4|If-Range: " + LAST_MODIFIED, "206 bytes 0-4/26 [abcde]",
							"206 bytes 0-4/26 [abcde]", "206 bytes 0-4/26 [abcde]" },
					// A range of a resource changed since: the whole (Jetty: the range).
					{ "Range: bytes=0-4|If-Range: Sat, 05 Nov 1994 08:49:37 GMT", "200", "200",
							"206 bytes 0-4/26 [abcde]" },
					{ "Range: bytes=0-4|If-Modified-Since: " + LAST_MODIFIED, "304", "304", "304" } };
			for (String[] row : conditional) {
				Http.Reply reply = Http.get(port, "/app/notes.txt", row[0].split("\\|"));
				// the parts of several ranges by the type of their whole
				String range = reply.field("Content-Range");
				String told = reply.status
						+ (range != null ? " " + range
								: reply.status == 206 ? " " + reply.field("Content-Type")
									.substring(0, reply.field("Content-Type").indexOf(';')) : "")
						+ (reply.status == 206 && range != null ? " [" + reply.text() + "]" : "");
				assertThat(told).as(row[0]).isEqualTo(measured(container, row[1], row[2], row[3]));
			}
			// A range is of a GET (Jetty: of a HEAD too), of a resource of some length
			// (Jetty: none of an empty one).
			assertThat(Http.send(port, "HEAD", "/app/notes.txt", null, "Range: bytes=0-4").status)
				.isEqualTo(measured(container, 200, 200, 206));
			assertThat(Http.get(port, "/app/empty.txt", "Range: bytes=0-4").status)
				.isEqualTo(measured(container, 200, 200, 416));
			// The 304: its time here; Tomcat's entity tag; Jetty's length.
			assertThat(served(Http.get(port, "/app/notes.txt", "If-Modified-Since: " + LAST_MODIFIED)))
				.isEqualTo(measured(container, "304 Last-Modified: " + LAST_MODIFIED + " []", "304 []",
						"304 Content-Length: 0 Accept-Ranges: bytes []"));
			// Included, the octets alone, through the including servlet's writer;
			// forwarded,
			// an answer of its own; by name, the resource of the request's own path.
			assertThat(Http.get(port, "/app/including/x?to=/style.css").text()).isEqualTo("[body { color: red }]");
			assertThat(Http.get(port, "/app/including/x?to=/missing.txt").text()).isEqualTo("[FileNotFoundException]");
			assertThat(Http.get(port, "/app/including/x?to=/sub/").text()).isEqualTo("[<h1>sub</h1>]");
			Http.Reply forwarded = Http.get(port, "/app/including/x?to=/notes.txt&forward", "Range: bytes=0-4");
			assertThat(forwarded.status + " " + forwarded.field("Content-Range") + " " + forwarded.text())
				.isEqualTo("206 bytes 0-4/26 abcde");
			assertThat(Http.get(port, "/app/including/x?forward").status).isEqualTo(404);
			// What the context answers of its resources.
			String root = documentRoot.toRealPath().toString();
			assertThat(Http.get(port, "/app/members").text().replace(root, "ROOT").split("\n")).containsExactly(
					"getResource /style.css file:ROOT/style.css",
					"getResource style.css " + measured(container, "MalformedURLException", "file:ROOT/style.css",
							"MalformedURLException"),
					"getResource /missing null", "getResource /sub file:ROOT/sub/",
					"getResource /sub/../style.css file:ROOT/style.css",
					"getResource /../x " + measured(container, "null", "IllegalArgumentException", "null"),
					"getResource /style.css/ " + measured(container, "null", "null", "file:ROOT/style.css/"),
					"getResourceAsStream /notes.txt [abcdefghijklmnopqrstuvwxyz]",
					"getResourceAsStream notes.txt "
							+ measured(container, "null", "[abcdefghijklmnopqrstuvwxyz]", "null"),
					"getResourceAsStream /sub null",
					"getResourcePaths / [/META-INF/, /WEB-INF/, /a b.txt, /empty.txt, /index.html, /noext, /notes.txt, /nowelcome/, /style.css, /sub/]",
					"getResourcePaths /sub [/sub/index.html, /sub/page.html]",
					"getResourcePaths /missing/ " + measured(container, "null", "null", "[]"),
					"getResourcePaths /style.css " + measured(container, "null", "null", "[]"),
					"getRealPath /style.css ROOT/style.css", "getRealPath style.css ROOT/style.css",
					"getRealPath /missing " + measured(container, "ROOT/missing", "ROOT/missing", "null"),
					"getRealPath / ROOT/",
					"getRealPath /sub " + measured(container, "ROOT/sub", "ROOT/sub", "ROOT/sub/"),
					"getRealPath /../x null");
		}
		finally {
			running.stop();
		}
		if (container == Container.HTTPSERVER) {
			compiledAheadOfTime(work);
		}
	}

	/**
	 * The resources of the class path where there is none to read: a program compiled
	 * ahead of time, which reads what its loader carried. A directory reads as the names
	 * of its entries, an archive's as nothing; a resource is taken as modified when the
	 * server was made.
	 */
	private static void compiledAheadOfTime(Path work) throws Exception {
		Path classes = work.resolve("classes");
		files(classes, "META-INF/resources/index.html", "<h1>classes</h1>", "META-INF/resources/deep/index.html",
				"<h1>deep</h1>", "META-INF/resources/bare/file.txt", "bare", "META-INF/resources/both.txt",
				"from classes");
		Path archive = work.resolve("library.jar");
		jar(archive, "META-INF/resources/both.txt", "from the archive", "META-INF/resources/shelf/index.html",
				"<h1>shelf</h1>", "META-INF/resources/shelf/book.txt", "book");
		ClassLoader before = Thread.currentThread().getContextClassLoader();
		String classPath = System.getProperty("java.class.path");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL(), archive.toUri().toURL() },
				before)) {
			Thread.currentThread().setContextClassLoader(loader);
			System.clearProperty("java.class.path");
			long made = System.currentTimeMillis() / 1000 * 1000;
			Container.Running running = Container.HTTPSERVER.start("", context -> {
			});
			try {
				int port = running.port;
				Http.Reply home = Http.get(port, "/");
				assertThat(home.text()).isEqualTo("<h1>classes</h1>");
				assertThat(home.field("Content-Type")).isEqualTo("text/html");
				long modified = HttpDates.parse(home.field("Last-Modified"));
				assertThat(modified).isBetween(made, System.currentTimeMillis());
				assertThat(Http.get(port, "/", "If-Modified-Since: " + home.field("Last-Modified")).status)
					.isEqualTo(304);
				// A directory: its listing's first name is a resource below it.
				assertThat(Http.get(port, "/deep").field("Location")).isEqualTo("/deep/");
				assertThat(Http.get(port, "/deep/").text()).isEqualTo("<h1>deep</h1>");
				assertThat(Http.get(port, "/bare").field("Location")).isEqualTo("/bare/");
				assertThat(Http.get(port, "/bare/").status).isEqualTo(404);
				// The first place holding a name, then an archive's; its directory, which
				// reads
				// as nothing, by the welcome file below it.
				assertThat(Http.get(port, "/both.txt").text()).isEqualTo("from classes");
				assertThat(Http.get(port, "/shelf/book.txt").text()).isEqualTo("book");
				assertThat(Http.get(port, "/shelf").field("Location")).isEqualTo("/shelf/");
				assertThat(Http.get(port, "/shelf/").text()).isEqualTo("<h1>shelf</h1>");
			}
			finally {
				running.stop();
			}
		}
		finally {
			System.setProperty("java.class.path", classPath);
			Thread.currentThread().setContextClassLoader(before);
		}
	}

	@ParameterizedTest
	@EnumSource(Container.class)
	void theApplicationIsInitializedServedByManyThreadsAndDestroyed(Container container) throws Exception {
		Application application = new Application();
		Container.Running running = container.start("/app", application::register);
		try {
			assertThat(Http.get(running.port, "/app/listened").text()).isEqualTo("initialized");
			assertThat(Http.get(running.port, "/app/session?case=create").status).isEqualTo(200);
			// Two requests that each wait for the other are answered: one thread each.
			ExecutorService clients = Executors.newFixedThreadPool(2);
			try {
				Future<Http.Reply> one = clients.submit(() -> Http.get(running.port, "/app/together"));
				Future<Http.Reply> other = clients.submit(() -> Http.get(running.port, "/app/together"));
				assertThat(one.get(30, TimeUnit.SECONDS).text()).isEqualTo("together");
				assertThat(other.get(30, TimeUnit.SECONDS).text()).isEqualTo("together");
			}
			finally {
				clients.shutdownNow();
			}
		}
		finally {
			running.stop();
		}
		// A stop ends the sessions in memory, telling their listeners (Jetty keeps
		// them).
		assertThat(application.sessionEvents.get(application.sessionEvents.size() - 1))
			.isEqualTo(measured(container, "removed a=two", "removed a=two", "replaced a=one by two"));
		// The listeners, the filters, the servlets loaded on startup by their numbers;
		// the rest of the order is the container's.
		assertThat(application.events).startsWith("context initialized probe")
			.containsSubsequence("init filter url x", "init first hello", "init second null")
			.contains("request destroyed initialized", "destroy first", "destroy filter url")
			.endsWith("context destroyed");
		if (container == Container.HTTPSERVER) {
			// Here: a servlet is initialized by its first request and everything is
			// destroyed in the reverse of the order it was registered in.
			assertThat(application.events).containsExactly("context initialized probe", "init filter named null",
					"init filter after null", "init filter url x", "init filter wrapping null",
					"init filter forwarded null", "init filter included null", "init first hello", "init second null",
					"init listened null", "request destroyed initialized", "init session null", "init together null",
					"destroy first", "destroy second", "destroy listened", "destroy session", "destroy together",
					"destroy filter included", "destroy filter forwarded", "destroy filter wrapping",
					"destroy filter url", "destroy filter after", "destroy filter named", "context destroyed");
		}
	}

}
