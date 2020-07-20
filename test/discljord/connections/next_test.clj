(ns discljord.connections.next-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as logi]
            [discljord.connections.websocket :as ws :refer [Websocket]]
            [discljord.connections.next :as d.c.n :refer [gateway-lifecycle]]
            [shrubbery.core :refer :all]))

(defn mock-websocket [] (mock Websocket {:send-msg :ok}))

(deftest MockWebsocket

  (testing "mock websocket"

    ;; mostly this is just me figuring out how to use shrubbery
    ;; testing this namespace will rely somewhat on knowing the websocket protocol is being used correctly

    (let [mock-websocket (mock-websocket)]
      ;; do the calls
      (ws/send-msg mock-websocket nil)
      (ws/recv-msgs mock-websocket nil)
      (ws/close mock-websocket)

      ;; check the things
      (is (received? mock-websocket ws/send-msg))
      (is (received? mock-websocket ws/recv-msgs))
      (is (received? mock-websocket ws/close)))))

;; TEST DATA
(def ws-hello-response
  {:d {:heartbeat-interval 1000}})

(def ws-ready-response
  {:d {:session-id 1000}})

(def ws-dispatch-response
  {:op :event-dispatch                                      ; TODO opcode
   :d  {}})

(def ws-invalid-session-response
  {:op :invalid-session                                     ; TODO opcode
   :d  {}})

(def ws-reconnect-response
  {:op :reconnect                                           ; TODO opcode
   :d  {}})

(defn base-state
  ([] (base-state ::d.c.n/lifecycle.disconnected))
  ([lifecycle]
   {::d.c.n/websocket   (mock-websocket)
    ::d.c.n/ws-chan     (a/chan 100)
    ::d.c.n/output-chan (a/chan 100)
    ::d.c.n/lifecycle   lifecycle}))

(defn with-responses [{::d.c.n/keys [ws-chan] :as state} & responses]
  (a/onto-chan! ws-chan responses)
  state)

(defn with-resume-session-id [state]
  (assoc state ::d.c.n/resume-session-id 123123))

(deftest GatewayLifecycleStages

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
                        (with-responses ws-hello-response))]
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
                        (with-responses ws-ready-response))]
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
                            (with-responses ws-ready-response))
              websocket (::d.c.n/websocket state)]
          (a/<!! (gateway-lifecycle state))
          (is (received? websocket ws/send-msg))))

      (testing "connected: receives event dispatch -> connected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connected)
                        (with-responses ws-dispatch-response))]
          (is (= ::d.c.n/lifecycle.connected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "connected: receives invalid session -> disconnected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connected)
                        (with-responses ws-invalid-session-response))]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "connected: receives reconnect -> disconnected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connected)
                        (with-responses ws-reconnect-response))]
          (is (= ::d.c.n/lifecycle.disconnected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "connected: outputs event dispatch"
        (let [state  (-> (base-state ::d.c.n/lifecycle.connected)
                         (with-responses ws-dispatch-response))
              output (::d.c.n/output-chan state)]
          (a/<!! (gateway-lifecycle state))
          (is (= (:d ws-dispatch-response)
                 (a/<!! output)))))

      (testing "connected: has resume state id -> resuming state"
        (let [state (-> (base-state ::d.c.n/lifecycle.connected)
                        with-resume-session-id)]
          (is (= ::d.c.n/lifecycle.resuming
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "resuming: -> connected state"
        (let [state (-> (base-state ::d.c.n/lifecycle.resuming)
                        with-resume-session-id)]
          (is (= ::d.c.n/lifecycle.connected
                 (::d.c.n/lifecycle
                   (a/<!!
                     (gateway-lifecycle state)))))))

      (testing "resuming: calls websocket/send-msg"
        (let [state     (base-state ::d.c.n/lifecycle.resuming)
              websocket (::d.c.n/websocket state)]
          (a/<!! (gateway-lifecycle state))
          (is (received? websocket ws/send-msg))))
      )))


(defn execute-lifecycle-until [stage state until-fn]
  (a/<!!
    (a/go-loop [[stage state] [stage state]]
      (let [s (a/<! (gateway-lifecycle stage state))]
        (if (until-fn s)
          s
          (recur s))))))

(defn until-stage [target-stage [stage _ :as s]]
  (when-not (= target-stage stage)
    s))

(deftest GateWayLifecycleWhole

  (binding [log/*logger-factory* logi/disabled-logger-factory]
    (with-redefs [d.c.n/retry-delay-ms 0
                  d.c.n/timeout-ms     100]

      (testing "reaches connected state"
        (let [state (-> (base-state))]))
      )))