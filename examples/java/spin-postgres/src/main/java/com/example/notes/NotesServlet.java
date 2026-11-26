// GET / lists the notes (?q= those whose title holds a word), POST / adds one for each
// title= it is given, all of them or none.

package com.example.notes;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class NotesServlet extends HttpServlet {

    private final Notes notes;

    NotesServlet(Notes notes) {
        this.notes = notes;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String server = this.notes.server();
            List<Notes.Note> found = this.notes.find(Optional.ofNullable(request.getParameter("q")));
            PrintWriter out = text(response);
            out.println(server);
            for (Notes.Note note : found) {
                out.println(note.id() + "  " + note.added() + "  " + note.title());
            }
        }
        catch (SQLException e) {
            refused(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        String[] titles = Optional.ofNullable(request.getParameterValues("title")).orElse(new String[] { "" });
        try {
            List<Notes.Note> added = this.notes.add(List.of(titles));
            response.setStatus(HttpServletResponse.SC_CREATED);
            PrintWriter out = text(response);
            for (Notes.Note note : added) {
                out.println("added " + note.id() + ": " + note.title());
            }
        }
        catch (SQLException e) {
            refused(response, e);
        }
    }

    // 23514: a title is empty, which the table's check refuses
    private static void refused(HttpServletResponse response, SQLException e) throws IOException {
        response.setStatus("23514".equals(e.getSQLState()) ? HttpServletResponse.SC_BAD_REQUEST
                : HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        text(response).println("SQLSTATE " + e.getSQLState() + ": " + e.getMessage());
    }

    private static PrintWriter text(HttpServletResponse response) throws IOException {
        response.setContentType("text/plain; charset=UTF-8");
        return response.getWriter();
    }

}
