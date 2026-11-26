// A visit counter kept in PostgreSQL through plain JDBC, written for a host that calls
// the program for each request: a Cloudflare Worker, whose sockets are connect() of
// cloudflare:sockets and whose database is a Hyperdrive binding.
//
// A Worker's socket lives as long as the request that opened it, so each request makes
// its connection and closes it: the pool is Hyperdrive's. And a Worker has no
// environment, so the database's address is the request's -- the field x-database-url,
// which src/index.js sets from the binding -- or, where the program runs as a process
// (a JDK, the interpreter, a native executable), the variable DATABASE_URL. Either form:
//
//   postgresql://user:password@host:port/database   (a Hyperdrive connection string)
//   jdbc:postgresql://host:port/database?user=..&password=..
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class Visits {

    static Connection connect(String address) throws SQLException {
        if (address == null) {
            throw new SQLException("no x-database-url field and no DATABASE_URL: where is the database?");
        }
        if (address.startsWith("jdbc:")) {
            return DriverManager.getConnection(address);
        }
        URI uri = URI.create(address);
        Properties login = new Properties();
        String user = uri.getUserInfo();
        if (user != null) {
            int colon = user.indexOf(':');
            login.setProperty("user", colon < 0 ? user : user.substring(0, colon));
            if (colon >= 0) {
                login.setProperty("password", user.substring(colon + 1));
            }
        }
        return DriverManager.getConnection("jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath(),
                login);
    }

    static String visit(Connection connection, String path) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists visit (path text primary key, count bigint not null)");
        }
        try (PreparedStatement upsert = connection.prepareStatement("""
                insert into visit (path, count) values (?, 1)
                on conflict (path) do update set count = visit.count + 1
                returning count""")) {
            upsert.setString(1, path);
            try (ResultSet row = upsert.executeQuery()) {
                row.next();
                return path + " " + row.getLong(1);
            }
        }
    }

    static void answer(HttpExchange exchange) throws IOException {
        String address = exchange.getRequestHeaders().getFirst("x-database-url");
        int status = 200;
        String said;
        try (Connection connection = connect(address != null ? address : System.getenv("DATABASE_URL"))) {
            said = visit(connection, exchange.getRequestURI().getPath());
        }
        catch (SQLException e) {
            status = 500;
            said = String.valueOf(e);
        }
        byte[] body = (said + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public static void main(String[] args) throws IOException {
        String port = System.getenv("PORT");
        HttpServer server = HttpServer.create(new InetSocketAddress(port == null ? 8080 : Integer.parseInt(port)), 0);
        server.createContext("/", Visits::answer);
        server.start();
        System.out.println("Listening on http://localhost:" + server.getAddress().getPort() + "/");
    }

}
