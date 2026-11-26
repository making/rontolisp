// Notes kept in PostgreSQL through plain JDBC, where the connection is the host's: with
// jdbc-spin-postgres on the path, DriverManager's connection runs its statements on
// Spin's spin:postgres/postgres. The program opens no socket and speaks no PostgreSQL
// protocol; Spin connects, pools the connections and, when the URL says sslmode=require,
// wraps them in TLS. It names no class of a driver: under a JDK the same program runs
// over a socket, through the other driver on its path (README.md).
//
//   DATABASE_URL  jdbc:postgresql://host:port/database?user=...&password=...[&sslmode=require]
//   DATABASE_CA   a PEM certificate the server's is verified against (optional)
//   PORT          where a JDK listens (8080); Spin listens itself
//
// Spin hands them in by `spin up --env`.

package com.example.notes;

import java.util.Properties;

import am.ik.servlet.httpserver.ServletHttpServer;

public class App {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("DATABASE_URL");
        if (url == null) {
            throw new IllegalStateException("no DATABASE_URL: spin up --env DATABASE_URL=jdbc:postgresql://...");
        }
        Properties info = new Properties();
        String certificate = System.getenv("DATABASE_CA");
        if (certificate != null) {
            info.setProperty("sslrootcert", certificate);
        }
        Notes notes = new Notes(url, info);
        notes.createTable();
        String port = System.getenv("PORT");
        ServletHttpServer server = ServletHttpServer.builder()
            .port(port == null ? 8080 : Integer.parseInt(port))
            .servlet("notes", new NotesServlet(notes), servlet -> servlet.urlPatterns("/"))
            .start();
        System.out.println("Listening on http://localhost:" + server.port() + "/");
    }

}
