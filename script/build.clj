(ns build
  "Generate the session with the real engine, render the page, write public/.

  Run: clojure -M:build

  This is a JVM build step, not an operational script — it exists to execute
  `torihiki` itself, which is the thing being demonstrated. The engine's own
  cross-runtime parity check (`torihiki.parity`) is what establishes that the
  ClojureScript path agrees with this one."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [torihiki-terminal.session :as sess]
            [torihiki-terminal.view :as view]))

(def ^:const blocks 240)

(defn- frame->js
  "Only the fields the replay script touches. Sending the whole frame would
  put a few hundred kilobytes of book depth on the wire for no reason."
  [f]
  {:h (:height f) :l (:last f) :m (:mark f) :o (:oracle f) :e (:equity f)
   :fd (:funding f) :r (:resting f) :rt (subs (:root f) 0 32)
   :b (mapv (juxt :level :qty :cum) (:bids f))
   :a (mapv (juxt :level :qty :cum) (:asks f))
   :t (mapv (juxt :level :qty :side :ts) (:trades f))
   :p [(get-in f [:position :size]) (get-in f [:position :entry])
       (get-in f [:position :upnl])]})

(defn- json
  "A tiny JSON writer. The payload is integers, vectors and short hex strings
  — pulling in a dependency to serialise that would cost more than it saves."
  [x]
  (cond
    (map? x) (str "{" (str/join "," (for [[k v] x] (str (json (name k)) ":" (json v)))) "}")
    (sequential? x) (str "[" (str/join "," (map json x)) "]")
    (string? x) (str "\"" (str/escape x {\" "\\\"" \\ "\\\\"}) "\"")
    (nil? x) "null"
    :else (str x)))

(defn -main [& _]
  (println "generating" blocks "blocks with the real engine...")
  (let [session (sess/generate blocks)
        html (view/render-page session)
        payload (json {:frames (mapv frame->js (:frames session))
                       :tick sess/tick-usd-cents
                       :lots sess/lots-per-unit})
        replay (slurp (io/resource "replay.js"))
        ;; the payload and the replay script go in just before </body>, so the
        ;; page is complete and readable before a single byte of script runs
        out (str/replace html "</body>"
                         (str "<script id=\"tk-data\" type=\"application/json\">"
                              payload "</script><script>" replay "</script></body>"))]
    (io/make-parents "public/index.html")
    (spit "public/index.html" out)
    (println "wrote public/index.html"
             (format "(%.1f KB, %d frames)" (/ (count out) 1024.0) blocks))
    (println "final state root:" (get-in session [:meta :final-root]))))
