(ns discljord.connections.websocket)

(defprotocol Websocket
  (send-msg [ws msg]
    "Send a message over the websocket")
  (subscribe-opcodes [ws ops chan]
    "Subscribes the given channel to the given opcodes (collection).
    Channel will be closed when the websocket is closed." )
  (close [ws]
    "Closes the websocket connection."))

(defn get-websocket
  [url]
  (reify Websocket
    (send-msg [_ msg]
      :todo)
    (subscribe-opcodes [_ _ _]
      :todo)
    (close [_])))
