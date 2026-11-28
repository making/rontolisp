import am.ik.servlet.httpserver.ServletHttpServer;
import jakarta.servlet.http.*;
public class HelloServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
  throws java.io.IOException {
    res.setContentType("text/plain;charset=UTF-8");
    res.getWriter().println("Hello World!");
  }
  void main() throws Exception {
    ServletHttpServer.builder().port(8080)
      .servlet("hello", new HelloServlet(), s -> s.urlPatterns("/"))
      .start();
  }
}
