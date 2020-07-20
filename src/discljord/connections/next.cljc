(ns discljord.connections.next
  (:require
    [discljord.connections.websocket :as d.c.ws]
    [clojure.core.async :as a]
    [clojure.tools.logging :as log]))

(def retry-delay-ms 5000)
(def timeout-ms 10000)

(defn- take-next-or-timeout
  "Attempts to take a value from the applied channel within `timeout-ms`, otherwise
  returns `::timeout`"
  [ch]
  (let [timeout (a/timeout timeout-ms)]
    (a/go
      (a/alt!
        timeout ::timeout
        ch ([x] x)))))

(defn- transition-disconnected
  "Transforms the given state to a disconnected one, returning a vector containing the new
  state and the disconnected stage keyword. Can be used to create a new initial state."
  [{::keys [ws-chan websocket connection-attempts session-id]
    :or    {connection-attempts 0}
    :as    state}]
  (when websocket (d.c.ws/close websocket))
  (when ws-chan (a/close! ws-chan))
  (-> state
      (dissoc ::ws-chan)
      (dissoc ::websocket)
      (dissoc ::session-id)
      (assoc ::connection-attempts (inc connection-attempts))
      (assoc ::resume-session-id session-id)
      (assoc ::lifecycle ::lifecycle.disconnected)))

(defmulti gateway-lifecycle
  "A multimethod defining the different states of a Discord gateway connection in
  accordance with the docs at https://discord.com/developers/docs/topics/gateway#connecting-to-the-gateway.

  Each method describes a stage of the lifecycle and returns a channel that will receive a
  vector [next-stage next-state] to be used to reach the next stage of the lifecycle by calling
  `(gateway-lifecycle next-stage next-state)`. Conceptually this model is a state machine.

  To maintain the connection an external runner should execute gateway-lifecycle methods consecutively in a loop."
  ::lifecycle)

(defmethod gateway-lifecycle ::lifecycle.disconnected
  [{::keys [wss-url connection-attempts]
    :as    state}]
  (a/go
    ;; attempt to connect
    (try
      (log/trace "Attempting websocket connection...")
      (let [websocket (d.c.ws/get-websocket wss-url)
            ws-chan   (a/chan (a/sliding-buffer 10))]
        (d.c.ws/recv-msgs websocket ws-chan)
        (log/trace "Websocket connected")
        ;; transport is connected - transition to connecting
        (-> state
            (assoc ::ws-chan ws-chan)
            (assoc ::websocket websocket)
            (assoc ::lifecycle ::lifecycle.connecting)))
      (catch #?(:clj Exception :cljs js/Object) e
        (log/error (str "Failed websocket connection attempt " connection-attempts) e)
        (a/<! (a/timeout retry-delay-ms))
        (transition-disconnected state)))))

(defmethod gateway-lifecycle ::lifecycle.connecting
  [{::keys [ws-chan]
    :as    state}]
  (a/go
    (log/trace "Awaiting remote hello...")
    (let [hello              (a/<! (take-next-or-timeout ws-chan))
          heartbeat-interval (get-in hello [:d :heartbeat-interval])]
      (if heartbeat-interval
        (do
          (log/trace "Hello received")
          ;; TODO begin heartbeat
          ;; hello is good - transition to identifying
          (-> state
              (assoc ::heartbeat-interval heartbeat-interval)
              (assoc ::lifecycle ::lifecycle.identifying)))
        (do
          (log/warn "Hello failed. Disconnecting...")
          (transition-disconnected state))))))

(defmethod gateway-lifecycle ::lifecycle.identifying
  [{::keys [websocket ws-chan]
    :as    state}]
  (a/go
    (log/trace "Requesting identify")
    (d.c.ws/send-msg websocket :TODO-payload-here)
    (log/trace "Identify requested. Awaiting remote ready...")
    (let [ready (a/<! (take-next-or-timeout ws-chan))
          {:keys [session-id]} (:d ready)]
      (if session-id
        (do
          (log/trace "Ready received" ready)
          (-> state
              (assoc ::session-id session-id)
              (assoc ::lifecycle ::lifecycle.connected)))
        (do
          (log/warn (str "Identify failed. Disconnecting..."))
          (transition-disconnected state))))))

(defmethod gateway-lifecycle ::lifecycle.connected
  [{::keys [ws-chan session-id resume-session-id output-chan]
    :as    state}]
  (a/go
    (log/info (str "Session " session-id " connected"))
    (if resume-session-id
      (assoc state ::lifecycle ::lifecycle.resuming)
      (let [{:keys [op d] :as evt} (a/<! ws-chan)]
        (case op
          :event-dispatch (do (a/>! output-chan d)
                              state)
          :invalid-session (do (log/info "Session invalidated. Disconnecting...")
                               (transition-disconnected state))
          :reconnect (do (log/info "Reconnect signal received. Disconnecting...")
                         (transition-disconnected state))
          ;; TODO handle heartbeat and heartbeat ACK
          (do (log/warn (str "Unknown opcode  " op) evt)
              state))))))

(defmethod gateway-lifecycle ::lifecycle.resuming
  [{::keys [websocket resume-session-id]
      :as    state}]
  (a/go
    (log/trace (str "Requesting resume of session id " resume-session-id))
    (d.c.ws/send-msg websocket ::TODO-resume-payload)
    (-> state
        (dissoc ::resume-session-id)
        (assoc ::lifecycle ::lifecycle.connected))))
