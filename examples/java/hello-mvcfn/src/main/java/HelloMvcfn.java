import java.util.Optional;

import am.ik.servlet.httpserver.ServletHttpServer;

import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

void main() throws Exception {
    int port = Optional.ofNullable(System.getenv("PORT")).map(Integer::parseInt).orElse(8080);
    RouterFunction<ServerResponse> routes = RouterFunctions.route()
        .GET("/", request -> ServerResponse.ok().body("Hello World!"))
        .build();
    StaticWebApplicationContext context = new StaticWebApplicationContext();
    context.registerBean(DispatcherServlet.HANDLER_MAPPING_BEAN_NAME, HandlerMapping.class,
            () -> new RouterFunctionMapping(routes));
    ServletHttpServer server = ServletHttpServer.builder()
        .port(port)
        .servlet("dispatcherServlet", new DispatcherServlet(context), servlet -> servlet.urlPatterns("/"))
        .start();
    IO.println("Listening on http://localhost:" + server.port() + "/");
}
