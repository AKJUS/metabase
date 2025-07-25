(ns metabase.server.streaming-response-test
  (:require
   [clj-http.client :as http]
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [metabase.driver :as driver]
   [metabase.query-processor.pipeline :as qp.pipeline]
   [metabase.server.protocols :as server.protocols]
   [metabase.server.streaming-response :as streaming-response]
   [metabase.server.streaming-response.thread-pool :as thread-pool]
   [metabase.test :as mt]
   [metabase.test.http-client :as client]
   [metabase.util :as u])
  (:import
   (jakarta.servlet AsyncContext ServletOutputStream)
   (jakarta.servlet.http HttpServletResponse)
   (java.util.concurrent Executors)
   (org.apache.commons.lang3.concurrent BasicThreadFactory$Builder)))

(set! *warn-on-reflection* true)

(driver/register! ::test-driver)

(def ^:private canceled? (atom false))

(def ^:private thread-pool-size 5)

(defn- do-with-streaming-response-thread-pool! [thunk]
  (let [pool (Executors/newFixedThreadPool thread-pool-size
                                           (.build
                                            (doto (BasicThreadFactory$Builder.)
                                              (.namingPattern "streaming-response-test-thread-pool-%d")
                                              ;; Daemon threads do not block shutdown of the JVM
                                              (.daemon true))))]
    (with-redefs [thread-pool/thread-pool (constantly pool)]
      (try
        (thunk)
        (finally
          (.shutdownNow pool))))))

(defmacro ^:private with-streaming-response-thread-pool! {:style/indent 0} [& body]
  `(do-with-streaming-response-thread-pool! (fn [] ~@body)))

(defmacro ^:private with-test-driver-db! {:style/indent 0} [& body]
  `(mt/with-temp [:model/Database db# {:engine ::test-driver}]
     (mt/with-db db#
       (with-streaming-response-thread-pool!
         ~@body))))

(def ^:private start-execution-chan
  "A core.async channel that will get a message when query execution starts."
  (atom nil))

(defmacro ^:private with-start-execution-chan
  "Runs body with `chan-binding` bound to a core.async promise channel that will get a message once a query execution
  starts running on the streaming response thread pool."
  [[chan-binding] & body]
  `(mt/with-open-channels [chan# (a/promise-chan)]
     (try
       (reset! start-execution-chan chan#)
       (let [~chan-binding chan#]
         ~@body)
       (finally
         (reset! start-execution-chan nil)))))

(defmethod driver/execute-reducible-query ::test-driver
  [_driver {{{:keys [sleep]} :query} :native, database-id :database} _context respond]
  {:pre [(integer? sleep) (integer? database-id)]}
  (let [futur (future
                (try
                  (when-let [chan @start-execution-chan]
                    (a/>!! chan ::started))
                  (Thread/sleep (long sleep))
                  (respond {:cols [{:name "Sleep", :base_type :type/Integer}]} [[sleep]])
                  (catch InterruptedException e
                    (reset! canceled? ::interrupted-exception)
                    (throw e))))]
    (when-let [canceled-chan qp.pipeline/*canceled-chan*]
      (a/go
        (when (a/<! canceled-chan)
          (reset! canceled? ::canceled-chan-message)
          (future-cancel futur))))
    (u/deref-with-timeout futur 5000)))

(defmethod driver/connection-properties ::test-driver
  [& _]
  [])

(deftest basic-test
  (testing "Make sure our ::test-driver is working as expected"
    (with-test-driver-db!
      (is (= [[10]]
             (mt/rows
              (mt/user-http-request :lucky
                                    :post 202 "dataset"
                                    {:database (mt/id)
                                     :type     "native"
                                     :native   {:query {:sleep 10}}})))))))

(deftest truly-async-test
  (testing "StreamingResponses should truly be asynchronous, and not block Jetty threads while waiting for results"
    (with-test-driver-db!
      (let [num-requests       (+ thread-pool-size 20)
            remaining          (atom num-requests)
            session-token      (client/authenticate (mt/user->credentials :lucky))
            url                (client/build-url "dataset" nil)
            request            (client/build-request-map session-token
                                                         {:database (mt/id)
                                                          :type     "native"
                                                          :native   {:query {:sleep 2000}}}
                                                         nil)]
        (testing (format "%d simultaneous queries" num-requests)
          (dotimes [_ num-requests]
            (future (http/post url request)))
          (Thread/sleep 100)
          (let [timer (u/start-timer)]
            (is (= {:status "ok"} (client/client :get 200 "health")))
            (testing "Health endpoint should complete before the first round of queries completes"
              (is (> @remaining (inc (- num-requests thread-pool-size)))))
            (testing "Health endpoint should complete in under 500ms regardless of how many queries are running"
              (testing "(Usually this is under 100ms but might be a little over if CircleCI is being slow)"
                (let [elapsed-ms (u/since-ms timer)]
                  (is (< elapsed-ms 500)))))))))))

(deftest cancelation-test
  (testing "Make sure canceling a HTTP request ultimately causes the query to be canceled"
    (mt/test-helpers-set-global-values!
      (with-redefs [streaming-response/async-cancellation-poll-interval-ms 50]
        (with-test-driver-db!
          (reset! canceled? false)
          (with-start-execution-chan [start-chan]
            (let [url           (client/build-url "dataset" nil)
                  session-token (client/authenticate (mt/user->credentials :lucky))
                  request       (client/build-request-map session-token
                                                          {:database (mt/id)
                                                           :type     "native"
                                                           :native   {:query {:sleep 5000}}}
                                                          nil)
                  futur         (http/post url (assoc request :async? true) identity (fn [e] (throw e)))]
              (is (future? futur))
             ;; wait a little while for the query to start running -- this should usually happen fairly quickly
              (mt/wait-for-result start-chan (u/seconds->ms 15))
              (future-cancel futur)
             ;; check every 10ms, up to 1000ms, whether `canceled?` is now `true`
              (is (loop [[wait & more] (repeat 10 100)]
                    (or @canceled?
                        (when wait
                          (Thread/sleep (long wait))
                          (recur more))))))))))))

(def ^:private ^:dynamic *number-of-cans* nil)

(deftest ^:parallel preserve-bindings-test
  (testing "Bindings established outside the `streaming-response` should be preserved inside the body"
    (with-open [os (java.io.ByteArrayOutputStream.)]
      (let [streaming-response (binding [*number-of-cans* 2]
                                 (streaming-response/streaming-response nil [os _]
                                   (.write os (.getBytes (format "%s cans" *number-of-cans*) "UTF-8"))))
            complete-promise   (promise)]
        (server.protocols/respond streaming-response
                                  {:response      (reify HttpServletResponse
                                                    (setStatus [_ _])
                                                    (setHeader [_ _ _])
                                                    (getOutputStream [_]
                                                      (proxy [ServletOutputStream] []
                                                        (write
                                                          ([byytes]
                                                           (.write os ^bytes byytes))
                                                          ([byytes offset length]
                                                           (.write os ^bytes byytes offset length))))))
                                   :async-context (reify AsyncContext
                                                    (complete [_]
                                                      (deliver complete-promise true)))})
        (is (true?
             (deref complete-promise 1000 ::timed-out)))
        (is (= "2 cans"
               (String. (.toByteArray os) "UTF-8")))))))
