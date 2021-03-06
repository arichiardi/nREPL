(ns nrepl.sanity-test
  (:use clojure.test
        [nrepl.transport :only (piped-transports)])
  (:require (nrepl.middleware [interruptible-eval :as eval]
                              [session :as session])
            [nrepl.core :as repl]
            [clojure.set :as set])
  (:import (java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit)))

(println (format "Testing with Clojure v%s on %s" (clojure-version) (System/getProperty "java.version")))

(defn- internal-eval
  ([expr] (internal-eval nil expr))
  ([ns expr]
   (let [[local remote] (piped-transports)
         out (java.io.StringWriter.)
         err (java.io.StringWriter.)
         expr (if (string? expr)
                expr
                (binding [*print-meta* true]
                  (pr-str expr)))
         msg (merge {:code expr :transport remote}
                    (when ns {:ns ns}))
         resp-fn (if ns
                   (juxt :ns :value)
                   :value)]
     (eval/evaluate {#'*out* (java.io.PrintWriter. out)
                     #'*err* (java.io.PrintWriter. err)}
                    msg)
     (->> (repl/response-seq local 0)
          (map resp-fn)
          (cons (str out))
          (#(if (seq (str err))
              (cons (str err) %)
              %))))))

(deftest eval-sanity
  (try
    (are [result expr] (= result (internal-eval expr))
      ["" 3] '(+ 1 2)

      ["" nil] '*1
      ["" nil] '(do (def ^{:dynamic true} ++ +) nil)
      ["" 5] '(binding [++ -] (++ 8 3))

      ["" 42] '(set! *print-length* 42)
      ["" nil] '*print-length*)
    (finally (ns-unmap *ns* '++))))

(deftest specified-namespace
  (try
    (are [ns result expr] (= result (internal-eval ns expr))
      (ns-name *ns*) ["" [(str (ns-name *ns*)) 3]]
      '(+ 1 2)

      'user ["" ["user" '("user" "++")]]
      '(do
         (def ^{:dynamic true} ++ +)
         (map #(-> #'++ meta % str) [:ns :name]))

      (ns-name *ns*) ["" [(str (ns-name *ns*)) 5]]
      '(binding [user/++ -]
         (user/++ 8 3)))
    (finally (ns-unmap 'user '++))))

(deftest multiple-expressions
  (are [result expr] (= result (internal-eval expr))
    ["" 4 65536.0] "(+ 1 3) (Math/pow 2 16)"
    ["" 4 20 1 0] "(+ 2 2) (* *1 5) (/ *2 4) (- *3 4)"
    ["" nil] '*1))

(deftest stdout-stderr
  (are [result expr] (= result (internal-eval expr))
    ["5 6 7 \n 8 9 10\n" nil] '(println 5 6 7 \newline 8 9 10)
    ["user/foo\n" "" nil] '(binding [*out* *err*]
                             (prn 'user/foo))
    ["problem" "" :value] '(do (.write *err* "problem")
                               :value))
  (is (re-seq #"Exception: No such var: user/foo" (-> '(prn user/foo)
                                                      internal-eval
                                                      first))))

(deftest repl-out-writer
  (let [[local remote] (piped-transports)
        w (#'session/session-out :out :dummy-session-id remote)]
    (doto w
      .flush
      (.println "println")
      (.write "abcd")
      (.write (.toCharArray "ef") 0 2)
      (.write "gh" 0 2)
      (.write (.toCharArray "ij"))
      (.write "   klm" 5 1)
      (.write 32)
      .flush)
    (with-open [out (java.io.PrintWriter. w)]
      (binding [*out* out]
        (newline)
        (prn #{})
        (flush)))

    (is (= [(str "println" (System/getProperty "line.separator"))
            "abcdefghijm "
            "\n#{}\n"]
           (->> (repl/response-seq local 0)
                (map :out))))))

;; TODO
(comment
  (def-repl-test auto-print-stack-trace
    (is (= true (repl-value "(set! nrepl/*print-detail-on-error* true)")))
    (is (.contains (-> (repl "(throw (Exception. \"foo\" (Exception. \"nested exception\")))")
                       full-response
                       :err)
                   "nested exception")))

  (def-repl-test install-custom-error-detail-fn
    (->> (repl/send-with connection
                         (set! nrepl/*print-error-detail*
                               (fn [ex] (print "custom printing!")))
                         (set! nrepl/*print-detail-on-error* true))
         repl/response-seq
         doall)
    (is (= "custom printing!"
           (->> (repl/send-with connection
                                (throw (Exception. "foo")))
                full-response
                :err)))))
