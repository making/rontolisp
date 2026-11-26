import java.util.Optional;

import am.ik.servlet.httpserver.ServletHttpServer;
import jakarta.servlet.http.HttpSession;

void main() throws Exception {
    int port = Optional.ofNullable(System.getenv("PORT")).map(Integer::parseInt).orElse(8080);
    ServletHttpServer server = ServletHttpServer.builder()
        .port(port)
        .servlet("hello", (request, response) -> {
            HttpSession session = request.getSession();
            Integer visits = (Integer) session.getAttribute("visits");
            int count = visits == null ? 1 : visits + 1;
            session.setAttribute("visits", count);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().print("Hello World! Visit " + count + " of this session.");
        }, servlet -> servlet.urlPatterns("/"))
        .start();
    IO.println("Listening on http://localhost:" + server.port() + "/");
}
