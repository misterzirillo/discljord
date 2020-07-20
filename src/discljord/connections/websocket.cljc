(ns discljord.connections.websocket)

(defprotocol Websocket
  (send-msg [ws msg] "Send a message over the websocket")
  (recv-msgs [ws chan] "Receive messages on the provided channel")
  (close [ws] "Close the websocket connection"))

(defn get-websocket
  [url]
  (reify Websocket
    (send-msg [_ msg]
      :todo)
    (recv-msgs [_ chan]
      :todo)
    (close [_]
      :todo)))
