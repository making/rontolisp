import java.util.Optional;

import am.ik.servlet.httpserver.ServletHttpServer;

void main() throws Exception {
    int port = Optional.ofNullable(System.getenv("PORT")).map(Integer::parseInt).orElse(8080);
    ServletHttpServer server = ServletHttpServer.builder()
        .port(port)
        .servlet("hello", (request, response) -> response.getWriter().print("Hello World!"),
                servlet -> servlet.urlPatterns("/"))
        .start();
    IO.println("Listening on http://localhost:" + server.port() + "/");
}
