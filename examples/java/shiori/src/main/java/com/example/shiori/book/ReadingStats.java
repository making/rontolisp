// Shiori: the shelf in numbers, as of the database's today.
package com.example.shiori.book;

import java.time.LocalDate;

// The pages read count a finished book whole and any other to its current page.
public record ReadingStats(int total, int reading, int finishedThisYear, long pagesRead, LocalDate today) {
}
