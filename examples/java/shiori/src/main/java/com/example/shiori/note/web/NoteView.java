// Shiori: a note as note.mustache shows it.
package com.example.shiori.note.web;

import java.util.ArrayList;
import java.util.List;

import com.example.shiori.note.Note;
import com.example.shiori.web.Formats;

// The title is its book's, which the dashboard shows beside it.
public record NoteView(long id, boolean quote, boolean vertical, Integer page, String body, long bookId,
        String title, String created) {

    public static NoteView of(Note note) {
        boolean quote = note.kind() == Note.Kind.QUOTE;
        // a quote in Japanese is set top to bottom, as on manuscript paper
        return new NoteView(note.id(), quote, quote && Formats.japanese(note.body()), note.page(), note.body(),
                note.bookId(), note.bookTitle(), Formats.dotted(note.writtenOn()));
    }

    public static List<NoteView> of(List<Note> notes) {
        List<NoteView> views = new ArrayList<>();
        for (Note note : notes) {
            views.add(of(note));
        }
        return views;
    }

}
