(ns discljord.connections.next
  (:require
    [discljord.connections.websocket :as d.c.ws]
    [discljord.connections.util :refer [dupes]]
    [clojure.core.async :as a]
    [clojure.tools.logging :as log]))

(def retry-delay-ms 5000)                                   ; per the rate limiting docs
(def timeout-ms 10000)

(def heartbeat-opcodes #{1 11})
(def control-opcodes #{7 9 10})
(def dispatch-opcodes #{1})

(defn- with-timeout
  ([ch] (with-timeout (a/timeout timeout-ms) ch))
  ([timeout ch]
   (a/go
     (a/alt!
       ch ([x] x)
       timeout nil
       :priority true))))

(defn- with-halt
  [halt-ch other-ch]
  (a/go
    (a/alt!
      halt-ch nil
      other-ch ([x] x)
      :priority true)))

(defn- transition-disconnected
  [{::keys [websocket connection-attempts session-id control-ch]
    :or    {connection-attempts 0}
    :as    state}]
  (when websocket (d.c.ws/close websocket))
  (when control-ch (a/close! control-ch))
  (-> state
      (dissoc ::websocket ::session-id)
      (assoc ::connection-attempts (inc connection-attempts))
      (assoc ::resume-session-id session-id)
      (assoc ::lifecycle ::lifecycle.disconnected)))

(defmulti gateway-lifecycle
  "A multimethod defining the different states of a Discord gateway connection
   in accordance with the docs at https://discord.com/developers/docs/topics/gateway#connecting-to-the-gateway.

  Each method of the lifecycle returns a channel that receives the next state
  to be executed. To maintain the connection an external runner should execute
  gateway-lifecycle consecutively in a go-loop."
  ::lifecycle)

(defmethod gateway-lifecycle ::lifecycle.disconnected
  [{::keys [wss-url connection-attempts]
    :as    state}]
  (a/go
    ;; attempt to connect
    (try
      (log/info "Attempting websocket connection...")
      (let [websocket  (d.c.ws/get-websocket wss-url)
            control-ch (a/chan 10)]
        (d.c.ws/subscribe-opcodes websocket control-opcodes control-ch)
        (log/info "Websocket connected")
        ;; transport is connected - transition to connecting
        (-> state
            (assoc ::control-ch control-ch)
            (assoc ::websocket websocket)
            (assoc ::lifecycle ::lifecycle.connecting)))
      (catch #?(:clj Exception :cljs js/Object) e
        (log/error (str "Failed websocket connection attempt " connection-attempts) e)
        (a/<! (a/timeout retry-delay-ms))
        (transition-disconnected state)))))

(defmethod gateway-lifecycle ::lifecycle.connecting
  [{::keys [control-ch]
    :as    state}]
  (a/go
    (log/info "Awaiting remote hello...")
    (let [hello              (->> control-ch (with-timeout) (a/<!))
          heartbeat-interval (get-in hello [:d :heartbeat-interval])]
      (if heartbeat-interval
        (do
          (log/info "Hello received")
          (-> state
              (assoc ::heartbeat-interval heartbeat-interval)
              (assoc ::lifecycle ::lifecycle.identifying)))
        (do
          (log/warn "Hello failed. Disconnecting...")
          (transition-disconnected state))))))

(defmethod gateway-lifecycle ::lifecycle.identifying
  [{::keys [websocket control-ch resume-session-id]
    :as    state}]
  (a/go
    (d.c.ws/send-msg websocket :TODO-payload-here)
    (log/info "Identify requested. Awaiting remote ready...")
    (let [{{:keys [session-id]} :d} (->> control-ch (with-timeout) (a/<!))]
      (if session-id
        (do
          (log/info "Ready received")
          (when resume-session-id
            (log/info (str "Requesting resume of session id " resume-session-id))
            (d.c.ws/send-msg websocket ::TODO-resume-payload))
          (-> state
              (dissoc ::resume-session-id)
              (assoc ::session-id session-id)
              (assoc ::lifecycle ::lifecycle.connected)))
        (do
          (log/warn (str "Identify failed. Disconnecting..."))
          (transition-disconnected state))))))

(defmethod gateway-lifecycle ::lifecycle.connected
  [{::keys [control-ch session-id output-ch websocket]
    :as    state}]
  (a/go
    (log/info (str "Session " session-id " connected"))
    ;; connected - pipe dispatch events to the output channel
    (let [ch (a/chan 10)]
      (d.c.ws/subscribe-opcodes websocket dispatch-opcodes ch)
      ;; an intermediate channel is needed so the output channel remains open
      ;; if the websocket is closed. Seamless recovery for the consumer
      (a/pipe ch output-ch false)
      (let [{:keys [op] :as evt} (a/<! control-ch)]
        (case (or op evt)
          :invalid-session (log/info "Session invalidated. Disconnecting...")
          :reconnect (log/info "Reconnect signal received. Disconnecting...")
          (log/warn (str "Unknown opcode " op ". Disconnecting...")))
        (a/close! ch)
        (transition-disconnected state)))))

(defn begin-periodic-heartbeat
  "Begins a process that sends a websocket heartbeat at the specified interval.
  Returns a channel that closes if a stale connection is detected."
  [websocket heartbeat-interval halt-ch]
  (log/info "Beginning heartbeat with interval" heartbeat-interval)
  (let [heartbeat-in-ch     (a/chan)
        heartbeat-in-mult   (a/mult heartbeat-in-ch)
        heartbeat-in-req-ch (a/chan 1 (filter (comp (partial = :heartbeat)
                                                    :op)))
        heartbeat-in-ack-ch (a/chan (a/dropping-buffer 1) (filter (comp (partial = :heartbeat-ack)
                                                                        :op)))]

    (a/tap heartbeat-in-mult heartbeat-in-req-ch)
    (a/tap heartbeat-in-mult heartbeat-in-ack-ch)
    (d.c.ws/subscribe-opcodes websocket heartbeat-opcodes heartbeat-in-ch)

    ;; heartbeat request loop
    (a/go-loop []
      (when (a/<! (with-halt halt-ch heartbeat-in-req-ch))
        (d.c.ws/send-msg websocket :heartbeat)
        (recur)))

    ;; periodic heartbeat loop
    ;; notice the dropping buffer for the ack channel:
    ;; this process should ignore multiple acks during the interval
    (a/go-loop []
      (d.c.ws/send-msg websocket :heartbeat)
      (let [timeout (a/timeout heartbeat-interval)
            {:keys [op]} (->> heartbeat-in-ack-ch
                              (with-timeout timeout)
                              (with-halt halt-ch)
                              (a/<!))]
        (a/<! timeout)                                      ; wait for timeout regardless
        (when (= op :heartbeat-ack)
          (recur))))))
