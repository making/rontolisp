// Shiori: the page of a request that went nowhere -- no such page, a refusal, a failure.
package com.example.shiori.web;

import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public record ErrorPage(int status, String heading, String message) {

    public static ServerResponse notFound(ServerRequest request) {
        return new ErrorPage(404, "この頁は落丁しています", request.path() + " という頁はありません。本棚から探してください。")
            .response(request);
    }

    public ServerResponse response(ServerRequest request) {
        return Page.of(Templates.ERROR, this).title(this.heading).status(this.status).response(request);
    }

}
