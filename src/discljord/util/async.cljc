(ns discljord.util.async
  (:require [clojure.core.async :as a]))

(defn with-halt
  [halt-ch other-ch]
  (a/go
    (a/alt!
      halt-ch nil
      other-ch ([x] x)
      :priority true)))
