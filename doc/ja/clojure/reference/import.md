# import

`(import class...)`

interop 向けにクラス名を登録し、`nil` を返します -- `ns` の `:import` 節が行うのと同じ配線を、パッケージ修飾のクラス名、`(package Class ...)` のリスト、または `[package Class ...]` のベクター（クォートの有無は問いません）でトップレベルに綴ったものです。

```clojure
(import java.util.Date)
(println (new Date)) ; the current instant
```
