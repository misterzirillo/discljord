(ns discljord.connections.next-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as logi]
            [discljord.connections.websocket :as ws :refer [Websocket]]
            [discljord.connections.next :as d.c.n :refer [gateway-lifecycle]]
            [shrubbery.core :refer :all]))

;; TEST DATA
(def ws-hello-response
  {:op :hello
   :d  {:heartbeat-interval 1000}})

(def ws-ready-response
  {:op :ready
   :d  {:session-id 1000}})

(def ws-dispatch-response
  {:op :event-dispatch                                      ; TODO opcode
   :d  {}})

(def ws-invalid-session-response
  {:op :invalid-session                                     ; TODO opcode
   :d  {}})

(def ws-reconnect-response
  {:op :reconnect                                           ; TODO opcode
   :d  {}})

(def ws-heartbeat-request
  {:op :heartbeat                                           ; TODO opcode
   :d  {}})

(def ws-heartbeat-ack
  {:op :heartbeat-ack                                       ; TODO opcode
   :d  {}})

(defn mock-websocket
  ([] (mock-websocket {}))
  ([opcodes->responses]
   (spy
     (reify Websocket
       (connect [_])
       (disconnect [_])
       (send-msg [_ _])
       (subscribe-opcodes [_ opcodes ch]
         (when-let [responses (and opcodes ch
                                   (get opcodes->responses opcodes))]
           (if (coll? responses)
             (a/onto-chan! ch responses)
             (a/pipe responses ch))))))))

(defprotocol SimulatedWebsocket
  (inject [ws msg-ch]))

(defn mock-websocket-simulated []
  (let [pub-ch           (a/chan 5)
        pub              (a/pub pub-ch :op)
        connected        (atom false)]
    (spy
      (reify
        Websocket
        (connect [_]
          (a/put! pub-ch ws-hello-response))
        (disconnect [_]
          (reset! connected false))
        (send-msg [_ msg]
          (case msg
            :heartbeat (a/put! pub-ch ws-heartbeat-ack)
            :identify (do (a/put! pub-ch {:op :ready :d {:session-id (rand-int 1000)}})
                          (reset! connected true))
            :noop)
          :ok)
        (subscribe-opcodes [_ opcodes ch]
          (doseq [op opcodes]
            (a/sub pub op ch)))

        SimulatedWebsocket
        (inject [_ msg-ch]
          (a/go-loop [msg (a/<! msg-ch)]
            (if (and msg @connected)
              (do (a/>! pub-ch msg)
                  (recur (a/<! msg-ch)))
              (when msg (recur msg)))))))))

(defn base-state
  ([] (base-state ::d.c.n/lifecycle.disconnected))
  ([lifecycle]
   {::d.c.n/halt-ch    (a/chan)
    ::d.c.n/websocket  (mock-websocket)
    ::d.c.n/control-ch (a/chan)
    ::d.c.n/output-ch  (a/chan 100)
    ::d.c.n/lifecycle  lifecycle}))

(defn bad-socket [state]
  (assoc state ::d.c.n/websocket (mock Websocket {:connect (throws Exception "Bad socket!")})))

(defn with-control-responses [{::d.c.n/keys [control-ch] :as state} & responses]
  (a/onto-chan! control-ch responses)
  state)

(defn with-resume-session-id [state]
  (assoc state ::d.c.n/resume-session-id 123123))

(defn with-simulated-websocket
  ([state]
   (assoc state ::d.c.n/websocket (mock-websocket-simulated)))
  ([state msg-ch]
   (let [sock (mock-websocket-simulated)]
     (inject sock msg-ch)
     (assoc state ::d.c.n/websocket sock))))

(deftest GatewayLifecycleTransitions

  (binding [log/*logger-factory* logi/disabled-logger-factory]
    (with-redefs [d.c.n/retry-delay-ms 0
                  d.c.n/timeout-ms     100]

      (testing "disconnected: bad initial connection -> disconnected state"
        (is (= ::d.c.n/lifecycle.disconnected
               (::d.c.n/lifecycle (a/<!! (gateway-lifecycle (-> (base-state)
                                                                bad-socket)))))))

      (testing "disconnected: ok initial connection -> connecting state"
        (is (= ::d.c.n/lifecycle.connecting
               (::d.c.n/lifecycle (a/<!! (gateway-lifecycle (base-state)))))))

      (testing "connecting: receives hello -> identifying state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connecting)
                        (with-control-responses ws-hello-response))]
          (is (= ::d.c.n/lifecycle.identifying
                 (::d.c.n/lifecycle (a/<!! (gateway-lifecycle state)))))))

      (testing "connecting: timeout -> disconnected state"
        (let [state (base-state ::d.c.n/lifecycle.connecting)]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle (a/<!! (gateway-lifecycle state)))))))

      (testing "identifying: receives ready -> connected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.identifying)
                        (with-control-responses ws-ready-response))]
          (is (= ::d.c.n/lifecycle.connected
                 (::d.c.n/lifecycle (a/<!! (gateway-lifecycle state)))))))

      (testing "identifying: timeout -> disconnected state"
        (let [state (base-state ::d.c.n/lifecycle.identifying)]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "identifying: calls websocket/send-msg"
        (let [state     (-> (base-state ::d.c.n/lifecycle.identifying)
                            (with-control-responses ws-ready-response))
              websocket (::d.c.n/websocket state)]
          (a/<!! (gateway-lifecycle state))
          (is (received? websocket ws/send-msg))))

      (testing "connected: receives invalid session -> disconnected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connected)
                        (with-control-responses ws-invalid-session-response))]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "connected: receives reconnect -> disconnected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connected)
                        (with-control-responses ws-reconnect-response))]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "connected: control-ch closes -> disconnected state"
        (let [state      (-> (base-state ::d.c.n/lifecycle.connected)
                             (with-control-responses ws-reconnect-response))
              control-ch (::d.c.n/control-ch state)]
          (a/close! control-ch)
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "connected: halt-ch closes -> disconnected state"
        (let [state   (-> (base-state ::d.c.n/lifecycle.connected)
                          (with-control-responses ws-reconnect-response))
              halt-ch (::d.c.n/halt-ch state)]
          (a/close! halt-ch)
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "identifying: resumable, calls websocket/send-msg"
        (let [state     (-> (base-state ::d.c.n/lifecycle.identifying) with-resume-session-id)
              websocket (::d.c.n/websocket state)]
          (a/<!! (gateway-lifecycle state))
          (is (received? websocket ws/send-msg))))
      )))

(defn n-executions [state n]
  (let [timeout (a/timeout 1000)]
    (a/<!!
      (a/go-loop [nn 0
                  state state]
        (if (< nn n)
          (recur (inc nn)
                 (a/alt!
                   (gateway-lifecycle state) ([s] s)
                   timeout (throw (Exception. "Test timed out"))
                   :priority true))
          state)))))

(defn execute-until
  ([state until-fn] (execute-until state until-fn (a/timeout 1000) true))
  ([state until-fn timeout-ch throw?]
   (let [execution (a/go-loop [state state]
                     (let [state (a/alt!
                                   (gateway-lifecycle state) ([s] s)
                                   timeout-ch (when throw?
                                                (throw (Exception. "Test timed out")))
                                   :priority true)]
                       (when state
                         (if (until-fn state)
                           state
                           (recur state)))))]
     (a/<!! execution))))

(defn until-lifecycle [lifecycle]
  (fn [state]
    (when (= lifecycle (::d.c.n/lifecycle state))
      state)))

(defn times [ufn n]
  (let [times (atom 0)]
    (fn [state]
      (when (ufn state)
        (swap! times inc)
        (when-not (< @times n)
          state)))))

(defn deliver-interval [interval items]
  (let [ch (a/chan)]
    (a/go-loop [items items]
      (if-let [it (first items)]
        (do
          (a/>! ch it)
          (a/<! (a/timeout interval))
          (recur (next items)))
        (a/close! ch)))
    ch))

(defn adelay [x ms]
  (a/go
    (a/<! (a/timeout ms))
    x))

(deftest GatewayLifecycleWhole

  (binding [log/*logger-factory* logi/disabled-logger-factory]
    (with-redefs [d.c.n/retry-delay-ms 0
                  d.c.n/timeout-ms     1000]

      (testing "normal startup"
        (let [state (-> (base-state)
                        with-simulated-websocket
                        (execute-until #(= ::d.c.n/lifecycle.connected (::d.c.n/lifecycle %))))]
          (is (= ::d.c.n/lifecycle.connected (::d.c.n/lifecycle state))
              "Connected state is reached")))

      (testing "reconnect request"
        (let [state     (-> (base-state)
                            (with-simulated-websocket (adelay ws-reconnect-response 100))
                            (execute-until (-> (until-lifecycle ::d.c.n/lifecycle.connected)
                                               (times 2))))
              websocket (::d.c.n/websocket state)]
          (is (= ::d.c.n/lifecycle.connected (::d.c.n/lifecycle state))
              "Connected state is reached again")
          (is (received? websocket ws/send-msg [:resume])
              "A request to resume the previous session is sent")))

      (testing "invalid session"
        (let [state     (-> (base-state)
                            (with-simulated-websocket (adelay ws-invalid-session-response 100))
                            (execute-until (-> (until-lifecycle ::d.c.n/lifecycle.connected)
                                               (times 2))))
              websocket (::d.c.n/websocket state)]
          (is (= ::d.c.n/lifecycle.connected (::d.c.n/lifecycle state))
              "Connected state is reached again")
          (is (received? websocket ws/send-msg [:resume])
              "A request to resume the previous session is sent")))

      (testing "multiple session event dispatch"
        (let [dispatches (deliver-interval 50 (repeat 10 ws-dispatch-response))
              reconnect  (adelay ws-reconnect-response 100)
              {::d.c.n/keys [output-ch]
               :as          state} (-> (base-state)
                                       (with-simulated-websocket (a/merge [reconnect dispatches])))]
          (execute-until state (constantly false) (a/timeout 1000) false)
          (is (= 10
                 (->> output-ch (a/take 10) (a/into []) a/<!! count)))))
      )))

(deftest HeartbeatRoutine
  (binding [log/*logger-factory* logi/disabled-logger-factory]
    (testing "heartbeat manual halt"
      (let [halt-ch (a/chan)
            ch      (d.c.n/begin-periodic-heartbeat (mock-websocket) 1000 halt-ch)]
        (a/close! halt-ch)
        (is (nil? (a/<!! ch)))))

    (testing "closes after interval"
      (let [ch (d.c.n/begin-periodic-heartbeat (mock-websocket) 10 (a/timeout 10000))]
        (is (nil? (a/<!! ch)))))

    (testing "sends requested heartbeat"
      (let [halt-ch   (a/timeout 100)
            websocket (mock-websocket {d.c.n/heartbeat-opcodes [ws-heartbeat-request]})]
        (a/<!! (d.c.n/begin-periodic-heartbeat websocket 1000 halt-ch))
        (is (= 2 (call-count websocket ws/send-msg)))))

    (testing "sends periodic heartbeats"
      (let [halt-ch   (a/timeout 100)
            websocket (mock-websocket {d.c.n/heartbeat-opcodes (deliver-interval
                                                                 10 (repeat 10 ws-heartbeat-ack))})]
        (a/<!! (d.c.n/begin-periodic-heartbeat websocket 10 halt-ch))
        (is (= 10 (call-count websocket ws/send-msg)))))
    ))
