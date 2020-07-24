(ns discljord.connections.websocket)

(defprotocol Websocket
  (connect [ws] "Initializes the websocket")
  (send-msg [ws msg]
    "Send a message over the websocket")
  (subscribe-opcodes [ws ops chan]
    "Subscribes the given channel to the given opcodes (collection).
    Channel will be closed when the websocket is closed." )
  (disconnect [ws]
    "Closes the websocket connection."))
