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
(def heartbeat-interval 1000)
(def ws-heartbeat-response
  {:d {:heartbeat-interval heartbeat-interval}})

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

(defn base-state []
  {::d.c.n/websocket (mock-websocket)})

(defn with-heartbeat-response [state]
  (let [ws-chan (a/chan)]
    (a/onto-chan! ws-chan [ws-heartbeat-response] false)
    (assoc state ::d.c.n/ws-chan ws-chan)))

(defn with-no-response [state]
  (let [ws-chan (a/chan)]
    (assoc state ::d.c.n/ws-chan ws-chan)))

(defn with-ready-response [state]
  (let [ws-chan (a/chan)]
    (a/onto-chan! ws-chan [ws-ready-response] false)
    (assoc state ::d.c.n/ws-chan ws-chan)))

(defn with-dispatch-response [state]
  (let [ws-chan (a/chan)]
    (a/onto-chan! ws-chan [ws-dispatch-response] false)
    (assoc state ::d.c.n/ws-chan ws-chan)))

(defn with-invalid-session-response [state]
  (let [ws-chan (a/chan)]
    (a/onto-chan! ws-chan [ws-invalid-session-response] false)
    (assoc state ::d.c.n/ws-chan ws-chan)))

(defn with-reconnect-response [state]
  (let [ws-chan (a/chan)]
    (a/onto-chan! ws-chan [ws-reconnect-response] false)
    (assoc state ::d.c.n/ws-chan ws-chan)))

(defn with-output-chan [state close?]
  (let [chan (a/chan 2)]
    (when close? (a/close! chan))
    (assoc state ::d.c.n/output-chan chan)))

(defn with-resume-session-id [state]
  (assoc state ::d.c.n/resume-session-id 123123))

(deftest GatewayLifecycleStages

  (binding [log/*logger-factory* logi/disabled-logger-factory]
    (with-redefs [d.c.n/retry-delay-ms 0
                  d.c.n/timeout-ms     100]

      (testing "disconnected: bad initial connection -> disconnected state"
        (with-redefs [ws/get-websocket (fn [_] (throw (Exception. "I failed")))]
          (is (= ::d.c.n/disconnected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/disconnected {})))))))

      (testing "disconnected: ok initial connection -> connecting state"
        (with-redefs [ws/get-websocket (fn [_] (mock Websocket {}))]
          (is (= ::d.c.n/connecting
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/disconnected {})))))))

      (testing "connecting: receives hello -> identifying state"
        (let [state (-> (base-state) with-heartbeat-response)]
          (is (= ::d.c.n/identifying
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/connecting state)))))))

      (testing "connecting: timeout -> disconnected state"
        (let [state (-> (base-state) with-no-response)]
          (is (= ::d.c.n/disconnected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/connecting state)))))))

      (testing "identifying: receives ready -> connected state"
        (let [state (-> (base-state) with-ready-response)]
          (is (= ::d.c.n/connected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/identifying state)))))))

      (testing "identifying: timeout -> disconnected state"
        (let [state (-> (base-state) with-no-response)]
          (is (= ::d.c.n/disconnected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/identifying state)))))))

      (testing "identifying: calls websocket/send-msg"
        (let [state     (-> (base-state) with-ready-response)
              websocket (::d.c.n/websocket state)]
          (a/<!! (gateway-lifecycle ::d.c.n/identifying state))
          (is (received? websocket ws/send-msg))))

      (testing "connected: receives event dispatch -> connected state"
        (let [state (-> (base-state) with-dispatch-response (with-output-chan true))]
          (is (= ::d.c.n/connected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/connected state)))))))

      (testing "connected: receives invalid session -> disconnected state"
        (let [state (-> (base-state) with-invalid-session-response)]
          (is (= ::d.c.n/disconnected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/connected state)))))))

      (testing "connected: receives reconnect -> disconnected state"
        (let [state (-> (base-state) with-reconnect-response)]
          (is (= ::d.c.n/disconnected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/connected state)))))))

      (testing "connected: outputs event dispatch"
        (let [state  (-> (base-state) with-dispatch-response (with-output-chan false))
              output (::d.c.n/output-chan state)]
          (a/<!! (gateway-lifecycle ::d.c.n/connected state))
          (is (= (:d ws-dispatch-response)
                 (a/<!! output)))))

      (testing "connected: has resume state id -> resuming state"
        (let [state (-> (base-state) with-resume-session-id)]
          (is (= ::d.c.n/resuming
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/connected state)))))))

      (testing "resuming: -> connected state"
        (let [state (-> (base-state) with-resume-session-id)]
          (is (= ::d.c.n/connected
                 (first
                   (a/<!!
                     (gateway-lifecycle ::d.c.n/resuming state)))))))

      (testing "resuming: calls websocket/send-msg"
        (let [state     (base-state)
              websocket (::d.c.n/websocket state)]
          (a/<!! (gateway-lifecycle ::d.c.n/resuming state))
          (is (received? websocket ws/send-msg))))
      )))