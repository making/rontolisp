// Shiori: the routes, written against Spring MVC's functional endpoints (WebMvc.fn).
package com.example.shiori;

import com.example.shiori.book.BookRepository;
import com.example.shiori.book.web.BookFormHandler;
import com.example.shiori.book.web.BookHandler;
import com.example.shiori.book.web.LibraryHandler;
import com.example.shiori.home.web.HomeHandler;
import com.example.shiori.note.NoteRepository;
import com.example.shiori.note.web.NoteHandler;
import com.example.shiori.web.Assets;
import com.example.shiori.web.ErrorPage;
import com.example.shiori.web.RequestFilters;

import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

// Every URL of the application and the handler answering it.
final class ShioriRouter {

    private final HomeHandler home;

    private final LibraryHandler library;

    private final BookHandler book;

    private final BookFormHandler bookForm;

    private final NoteHandler note;

    ShioriRouter(BookRepository books, NoteRepository notes) {
        this.home = new HomeHandler(books, notes);
        this.library = new LibraryHandler(books);
        this.book = new BookHandler(books, notes);
        this.bookForm = new BookFormHandler(books);
        this.note = new NoteHandler(notes, books);
    }

    RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
            .GET("/", this.home::dashboard)
            // a literal path before the variable one: /books/new would match /books/{id}
            .GET("/books", this.library::library)
            .POST("/books", this.bookForm::create)
            .GET("/books/new", this.bookForm::newBook)
            .GET("/books/{id}", this.book::show)
            .POST("/books/{id}", this.bookForm::update)
            .GET("/books/{id}/edit", this.bookForm::edit)
            .POST("/books/{id}/delete", this.bookForm::delete)
            .POST("/books/{id}/progress", this.book::progress)
            .POST("/books/{id}/rating", this.book::rating)
            .POST("/books/{id}/notes", this.note::add)
            .POST("/notes/{id}/delete", this.note::delete)
            // the URLs of the stylesheet and the script change with their text
            .GET("/static/app.css", Assets::stylesheet)
            .GET("/static/app.js", Assets::script)
            .GET("/favicon.svg", Assets::favicon)
            .route(RequestPredicates.all(), ErrorPage::notFound)
            .filter(RequestFilters::utf8)
            .filter(RequestFilters::sameOrigin)
            .onError(Exception.class, RequestFilters::failed)
            .build();
    }

}
