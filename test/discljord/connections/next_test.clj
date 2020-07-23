(ns discljord.connections.next-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as logi]
            [discljord.connections.websocket :as ws :refer [Websocket]]
            [discljord.connections.next :as d.c.n :refer [gateway-lifecycle]]
            [shrubbery.core :refer :all]))

(defn mock-websocket
  ([] (mock-websocket {}))
  ([opcodes->responses]
   (spy
     (reify Websocket
       (close [_])
       (send-msg [_ _])
       (subscribe-opcodes [_ opcodes ch]
         (when-let [responses (and opcodes ch
                                   (get opcodes->responses opcodes))]
           (if (coll? responses)
             (a/onto-chan! ch responses)
             (a/pipe responses ch))))))))

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

(defn base-state
  ([] (base-state ::d.c.n/lifecycle.disconnected))
  ([lifecycle]
   {::d.c.n/websocket  (mock-websocket)
    ::d.c.n/control-ch (a/chan 100)
    ::d.c.n/output-ch  (a/chan 100)
    ::d.c.n/lifecycle  lifecycle}))

(defn with-control-responses [{::d.c.n/keys [control-ch] :as state} & responses]
  (a/onto-chan! control-ch responses)
  state)

(defn with-resume-session-id [state]
  (assoc state ::d.c.n/resume-session-id 123123))

(deftest GatewayLifecycleTransitions

  (binding [log/*logger-factory* logi/disabled-logger-factory]
    (with-redefs [d.c.n/retry-delay-ms 0
                  d.c.n/timeout-ms     100]

      (testing "disconnected: bad initial connection -> disconnected state"
        (with-redefs [ws/get-websocket (fn [_] (throw (Exception. "I failed")))]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle (base-state))))))))

      (testing "disconnected: ok initial connection -> connecting state"
        (with-redefs [ws/get-websocket (fn [_] (mock Websocket {}))]
          (is (= ::d.c.n/lifecycle.connecting
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle (base-state))))))))

      (testing "connecting: receives hello -> identifying state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connecting)
                        (with-control-responses ws-hello-response))]
          (is (= ::d.c.n/lifecycle.identifying
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "connecting: timeout -> disconnected state"
        (let [state (base-state ::d.c.n/lifecycle.connecting)]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "identifying: receives ready -> connected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.identifying)
                        (with-control-responses ws-ready-response))]
          (is (= ::d.c.n/lifecycle.connected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

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


(defmacro with-socket-responses [responses & body]
  `(with-redefs [ws/get-websocket (fn [_#] (mock-websocket ~responses))]
     ~@body))

(deftest GatewayLifecycleWhole

  (binding [log/*logger-factory* logi/disabled-logger-factory]
    (with-redefs [d.c.n/retry-delay-ms 0
                  d.c.n/timeout-ms     100]

      (testing "normal startup"
        (with-socket-responses {d.c.n/control-opcodes [ws-hello-response ws-ready-response]}
          (let [state (-> (base-state)
                          (n-executions 3))]
            (is (= ::d.c.n/lifecycle.connected (::d.c.n/lifecycle state))
                "Connected state is reached"))))

      (testing "reconnect request"
        (with-socket-responses {d.c.n/control-opcodes [ws-hello-response ws-ready-response
                                                       ws-reconnect-response ws-hello-response
                                                       ws-ready-response]}
          (let [state     (-> (base-state)
                              (n-executions 7))
                websocket (::d.c.n/websocket state)]
            (is (= ::d.c.n/lifecycle.connected (::d.c.n/lifecycle state))
                "Connected state is reached again")
            (is (received? websocket ws/send-msg [::d.c.n/TODO-resume-payload])
                "A request to resume the previous session is sent"))))

      (testing "session invalidation"
        (with-socket-responses {d.c.n/control-opcodes [ws-hello-response ws-ready-response
                                                       ws-invalid-session-response ws-hello-response
                                                       ws-ready-response]}
          (let [state     (-> (base-state)
                              (n-executions 7))
                websocket (::d.c.n/websocket state)]
            (is (= ::d.c.n/lifecycle.connected (::d.c.n/lifecycle state))
                "Connected state is reached again")
            (is (received? websocket ws/send-msg [::d.c.n/TODO-resume-payload])
                "A request to resume the previous session is sent"))))
      )))

(defn deliver-interval [interval items]
  (let [ch (a/chan 2)]
    (a/go-loop [[it & itt] items]
      (when it
        (a/>! ch it)
        (a/<! (a/timeout interval))
        (recur itt)))
    ch))

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
