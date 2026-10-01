(let [p (promise)] (deliver p 7) (println @p))
