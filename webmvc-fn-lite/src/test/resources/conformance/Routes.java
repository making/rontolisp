package conformance;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.web.servlet.function.RequestPredicates.accept;
import static org.springframework.web.servlet.function.RequestPredicates.contentType;
import static org.springframework.web.servlet.function.RequestPredicates.param;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/** Routes over the whole surface of this module, asked of it and of Spring Framework. */
public class Routes {

	enum Colour {

		RED

	}

	static RouterFunction<ServerResponse> routes() {
		return RouterFunctions.route()
			// bodies and their types
			.GET("/", request -> ServerResponse.ok().body("Hello World!"))
			.GET("/text/latin", request -> ServerResponse.ok().body("café €"))
			.GET("/text/utf8",
					request -> ServerResponse.ok()
						.contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
						.body("café €"))
			.GET("/text/html", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body("<p>café</p>"))
			.GET("/text/json",
					request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body("{\"café\":1}"))
			.GET("/bytes", request -> ServerResponse.ok().body(new byte[] { 1, 2, 3 }))
			.GET("/bytes/png",
					request -> ServerResponse.ok().contentType(MediaType.valueOf("image/png")).body(new byte[] { 1, 2 }))
			.GET("/json/map", request -> ServerResponse.ok().body(document()))
			.GET("/json/list", request -> ServerResponse.ok().body(List.of(1, "two", List.of())))
			.GET("/json/problem",
					request -> ServerResponse.status(HttpStatus.CONFLICT)
						.contentType(MediaType.APPLICATION_PROBLEM_JSON)
						.body(Map.of("title", "taken")))
			.GET("/json/as-text",
					request -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).body(Map.of("a", 1)))
			// statuses and fields
			.GET("/status/{code}",
					request -> ServerResponse.status(Integer.parseInt(request.pathVariable("code"))).build())
			.GET("/created", request -> ServerResponse.created(URI.create("/tasks/1")).body("made"))
			.GET("/accepted", request -> ServerResponse.accepted().build())
			.GET("/no-content", request -> ServerResponse.noContent().build())
			.GET("/see-other", request -> ServerResponse.seeOther(URI.create("/elsewhere?a=b%20c")).build())
			.GET("/temporary", request -> ServerResponse.temporaryRedirect(URI.create("http://example.com/x")).build())
			.GET("/permanent", request -> ServerResponse.permanentRedirect(URI.create("/moved")).build())
			.GET("/bad-request", request -> ServerResponse.badRequest().body("no"))
			.GET("/not-found", request -> ServerResponse.notFound().build())
			.GET("/unprocessable", request -> ServerResponse.unprocessableContent().build())
			.GET("/fields",
					request -> ServerResponse.ok()
						.header("X-One", "1", "2")
						.header("x-one", "3")
						.headers(headers -> {
							headers.set("X-Two", "two");
							headers.add("X-Two", "too");
							headers.setBearerAuth("token");
						})
						.allow(HttpMethod.GET, HttpMethod.POST)
						.varyBy("Accept", "Origin")
						.contentLength(2)
						.body("ok"))
			.GET("/etag", request -> ServerResponse.ok().eTag("v1").body("tagged"))
			.GET("/from",
					request -> ServerResponse.from(ServerResponse.status(HttpStatus.ACCEPTED).header("X-From", "kept").build())
						.body("copied"))
			.GET("/written", request -> ServerResponse.ok().header("X-Written", "yes").build((servletRequest, servletResponse) -> {
				servletResponse.setContentType("text/plain;charset=UTF-8");
				servletResponse.getWriter().write("by hand");
				return null;
			}))
			.GET("/response", request -> {
				ServerResponse response = ServerResponse.status(HttpStatus.CREATED)
					.header("X-A", "a")
					.contentType(MediaType.TEXT_PLAIN)
					.build();
				return ServerResponse.ok()
					.body(response.statusCode().value() + " " + response.statusCode().is2xxSuccessful() + " "
							+ response.headers().getFirst("x-a") + " " + response.headers().getContentType() + " "
							+ response.cookies().isEmpty());
			})
			// the request
			.GET("/request", request -> ServerResponse.ok().body(describe(request)))
			.GET("/request/{id}/{name}", request -> ServerResponse.ok().body(describe(request)))
			.POST("/echo/text", request -> ServerResponse.ok().body(request.body(String.class)))
			.POST("/echo/bytes", request -> ServerResponse.ok().body(request.body(byte[].class)))
			.POST("/echo/json", contentType(MediaType.APPLICATION_JSON), request -> {
				Map<?, ?> read = request.body(Map.class);
				Map<String, Object> answer = new LinkedHashMap<>();
				for (Map.Entry<?, ?> member : read.entrySet()) {
					Object value = member.getValue();
					answer.put(member.getKey().toString(),
							(value == null) ? "null" : value.getClass().getSimpleName() + " " + value);
				}
				return ServerResponse.ok().body(answer);
			})
			.POST("/echo/list", request -> ServerResponse.ok().body(request.body(List.class)))
			.POST("/echo/any", request -> ServerResponse.ok().body(String.valueOf(request.body(Object.class))))
			// after the JSON converter, which Spring Framework's Jackson would write a record with
			.GET("/own", request -> ServerResponse.ok().contentType(OwnConverter.POINT).body(new OwnConverter.Point(1, 2)))
			.POST("/own",
					request -> ServerResponse.ok().contentType(OwnConverter.POINT).body(request.body(OwnConverter.Point.class)))
			.POST("/form", request -> ServerResponse.ok().body(new TreeMap<>(request.params()).toString()))
			// the patterns
			.GET("/files/{*rest}", request -> ServerResponse.ok().body("rest=" + request.pathVariable("rest")))
			.GET("/tree/**", request -> ServerResponse.ok().body("tree " + request.path()))
			.GET("/any/*/end", request -> ServerResponse.ok().body("star"))
			.GET("/any/*", request -> ServerResponse.ok().body("star at the end"))
			.GET("/one/a?c", request -> ServerResponse.ok().body("question"))
			.GET("/mixed/file-{name}.{ext}",
					request -> ServerResponse.ok().body(request.pathVariable("name") + " " + request.pathVariable("ext")))
			.GET("/glob/a*z", request -> ServerResponse.ok().body("glob"))
			.GET("/slash/", request -> ServerResponse.ok().body("with a slash"))
			.GET("/Case", request -> ServerResponse.ok().body("case"))
			.GET("no-slash", request -> ServerResponse.ok().body("given a slash"))
			.GET("/var/{a}/{b}", request -> ServerResponse.ok().body(new TreeMap<>(request.pathVariables()).toString()))
			// predicates
			.GET("/accepts", accept(MediaType.APPLICATION_JSON), request -> ServerResponse.ok().body(Map.of("as", "json")))
			.GET("/accepts", accept(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN),
					request -> ServerResponse.ok().body("as text"))
			.POST("/typed", contentType(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN),
					request -> ServerResponse.ok().body("typed"))
			.GET("/param", param("mode", "a"), request -> ServerResponse.ok().body("mode a"))
			.GET("/param", param("mode", mode -> mode.startsWith("b")), request -> ServerResponse.ok().body("mode b.."))
			.route(RequestPredicates.methods(HttpMethod.PUT, HttpMethod.PATCH).and(path("/either")),
					request -> ServerResponse.ok().body("either " + request.method()))
			.route(RequestPredicates.GET("/or/left").or(RequestPredicates.GET("/or/{right}")),
					request -> ServerResponse.ok().body("or " + new TreeMap<>(request.pathVariables())))
			.route(path("/negated/**").and(RequestPredicates.method(HttpMethod.GET).negate()),
					request -> ServerResponse.ok().body("not a GET"))
			.route(path("/headers").and(RequestPredicates.headers(headers -> headers.firstHeader("X-Key") != null)),
					request -> ServerResponse.ok().body("key " + request.headers().header("x-key")))
			.route(path("/variable/{v}").and(request -> request.pathVariable("v").equals("yes")),
					request -> ServerResponse.ok().body("the right side sees the left's variable"))
			.HEAD("/head", request -> ServerResponse.ok().header("X-Head", "yes").build())
			.PUT("/put/{id}", request -> ServerResponse.ok().body("put " + request.pathVariable("id")))
			.PATCH("/patch", request -> ServerResponse.ok().body("patch"))
			.DELETE("/delete/{id}", request -> ServerResponse.noContent().build())
			.OPTIONS("/options", request -> ServerResponse.ok().allow(HttpMethod.GET).build())
			// nesting
			.path("/api", api -> api.GET("", request -> ServerResponse.ok().body("api " + request.path()))
				.GET("/", request -> ServerResponse.ok().body("api/ " + request.path()))
				.GET("/items/{id}", request -> ServerResponse.ok().body("item " + new TreeMap<>(request.pathVariables())))
				.path("/v/{version}",
						versioned -> versioned
							.GET(request -> ServerResponse.ok().body("version " + new TreeMap<>(request.pathVariables())))
							.POST("/{id}", request -> ServerResponse.ok().body("versioned " + new TreeMap<>(request.pathVariables()))))
				.nest(accept(MediaType.APPLICATION_JSON),
						json -> json.GET("/nested", request -> ServerResponse.ok().body(Map.of("nested", true))))
				.GET("/nested", request -> ServerResponse.ok().body("nested, as text")))
			.path("/slashed/", slashed -> slashed.GET("", request -> ServerResponse.ok().body("slashed, empty"))
				.GET("/in", request -> ServerResponse.ok().body("slashed in")))
			.path("/starred/*", starred -> starred.GET("", request -> ServerResponse.ok().body("starred, empty"))
				.GET("/", request -> ServerResponse.ok().body("starred, slash"))
				.GET("/in", request -> ServerResponse.ok().body("starred in")))
			.path("/guarded",
					guarded -> guarded.GET("/in", request -> ServerResponse.ok().body("let in"))
						.filter((request, next) -> (request.headers().firstHeader(HttpHeaders.AUTHORIZATION) == null)
								? ServerResponse.status(HttpStatus.UNAUTHORIZED).build() : next.handle(request)))
			.add(RouterFunctions.route(RequestPredicates.GET("/added"), request -> ServerResponse.ok().body("added"))
				.andRoute(RequestPredicates.GET("/added/too"), request -> ServerResponse.ok().body("added too"))
				.andNest(path("/added/nest"),
						RouterFunctions.route(RequestPredicates.GET("/in"), request -> ServerResponse.ok().body("in"))))
			// filters, in their order, and errors
			.GET("/filtered", request -> ServerResponse.ok().body("seen by " + request.attribute("seen").orElse("nobody")))
			.GET("/throws/state", request -> {
				throw new IllegalStateException("boom");
			})
			.GET("/throws/argument", request -> {
				throw new IllegalArgumentException("bad");
			})
			.GET("/throws/status", request -> {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "taken");
			})
			.GET("/throws/status/bare", request -> {
				throw new ResponseStatusException(HttpStatus.GONE);
			})
			.GET("/throws/status/wrapped", request -> {
				throw new IllegalStateException(new ResponseStatusException(HttpStatus.FORBIDDEN, "inside"));
			})
			.GET("/throws/checked", request -> {
				throw new java.io.FileNotFoundException("gone");
			})
			.GET("/unwritable", request -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).body(List.of(1)))
			.GET("/unwritable/handled", request -> ServerResponse.ok().contentType(MediaType.TEXT_XML).body(List.of(1)))
			.GET("/null", request -> null)
			.before(request -> {
				request.attributes().put("seen", "before");
				return request;
			})
			.filter((request, next) -> {
				request.attributes().put("seen", request.attribute("seen").orElse("") + ", first");
				return next.handle(request);
			})
			.filter((request, next) -> {
				request.attributes().put("seen", request.attribute("seen").orElse("") + ", second");
				ServerResponse response = next.handle(request);
				return "wrap".equals(request.param("after").orElse(null))
						? ServerResponse.from(response).header("X-Wrapped", "yes").body("wrapped") : response;
			})
			.after((request, response) -> response)
			.onError(IllegalArgumentException.class,
					(ex, request) -> ServerResponse.badRequest().body(error(ex, request)))
			.onError(ex -> ex instanceof org.springframework.web.HttpMediaTypeNotAcceptableException
					&& ex.getMessage() != null, (ex, request) -> {
						if (request.path().endsWith("/handled")) {
							return ServerResponse.status(HttpStatus.NOT_ACCEPTABLE).body("cannot be written: " + request.path());
						}
						throw new IllegalStateException("not handled");
					})
			.build();
	}

	private static Map<String, Object> error(Throwable ex, ServerRequest request) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("error", ex.getMessage());
		error.put("path", request.path());
		return error;
	}

	private static Map<String, Object> document() {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("text", "café \"quoted\" back\\slash /slash \t\n\r\b\f \u0001 \u007f   😀");
		document.put("int", 42);
		document.put("long", 1234567890123L);
		document.put("double", 1.5);
		document.put("whole", 2.0);
		document.put("small", 1.0E-5);
		document.put("big", 1.0E21);
		document.put("float", 1.1f);
		document.put("nan", Double.NaN);
		document.put("true", true);
		document.put("null", null);
		document.put("enum", Colour.RED);
		document.put("char", 'c');
		document.put("array", new Object[] { 1, "a" });
		List<Object> list = new ArrayList<>();
		list.add(Map.of("k", List.of()));
		list.add(null);
		list.add(new LinkedHashMap<>());
		document.put("list", list);
		Map<Object, Object> keys = new LinkedHashMap<>();
		keys.put(1, "int");
		keys.put(true, "boolean");
		document.put("keys", keys);
		return document;
	}

	private static String describe(ServerRequest request) {
		StringBuilder text = new StringBuilder();
		text.append("method ").append(request.method()).append(' ').append(request.method() == HttpMethod.GET).append('\n');
		text.append("path ").append(request.path()).append('\n');
		text.append("uri ").append(request.uri().getPath()).append(' ').append(request.uri().getRawQuery()).append('\n');
		text.append("q ").append(request.param("q").orElse("-")).append(' ').append(request.param("none").isPresent()).append('\n');
		text.append("params ").append(new TreeMap<>(request.params())).append('\n');
		text.append("variables ").append(new TreeMap<>(request.pathVariables())).append('\n');
		text.append("x-many ").append(request.headers().header("X-Many")).append(' ').append(request.headers().firstHeader("x-none")).append('\n');
		text.append("accept ").append(request.headers().accept()).append('\n');
		text.append("content-type ").append(request.headers().contentType().isPresent()).append('\n');
		text.append("content-length ").append(request.headers().contentLength().isPresent()).append('\n');
		text.append("as-http-headers ").append(request.headers().asHttpHeaders().getFirst("X-MANY")).append('\n');
		text.append("attribute ").append(request.attribute("seen").isPresent()).append(' ').append(request.attributes().containsKey("seen")).append('\n');
		text.append("servlet ").append(request.servletRequest().getMethod()).append('\n');
		text.append("converters ").append(request.messageConverters().isEmpty()).append('\n');
		try {
			request.pathVariable("none");
		}
		catch (IllegalArgumentException ex) {
			text.append("no variable: ").append(ex.getMessage()).append('\n');
		}
		return text.toString();
	}

}
