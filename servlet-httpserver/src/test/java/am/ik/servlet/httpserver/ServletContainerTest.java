package am.ik.servlet.httpserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

	@BeforeAll
	static void start() throws Exception {
		for (Container container : Container.values()) {
			APPLICATION.put(container, container.start("/app", new Application()::register));
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
		if (container == Container.HTTPSERVER) {
			assertThat(busy.field("Retry-After")).isEqualTo("5");
			// Once committed, the response ends as it is (Tomcat cuts the chunks).
			assertThat(told(get(container, "/app/errors?case=after-commit")))
				.isEqualTo("200 Transfer-Encoding: chunked [sent]");
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

	@ParameterizedTest
	@EnumSource(Container.class)
	void theApplicationIsInitializedServedByManyThreadsAndDestroyed(Container container) throws Exception {
		Application application = new Application();
		Container.Running running = container.start("/app", application::register);
		try {
			assertThat(Http.get(running.port, "/app/listened").text()).isEqualTo("initialized");
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
					"init listened null", "request destroyed initialized", "init together null", "destroy first",
					"destroy second", "destroy listened", "destroy together", "destroy filter included",
					"destroy filter forwarded", "destroy filter wrapping", "destroy filter url", "destroy filter after",
					"destroy filter named", "context destroyed");
		}
	}

}
