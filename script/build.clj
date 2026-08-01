(ns build
  "Render the terminal shell. Run: clojure -M:build

  There is no session to generate any more. The page used to embed a recorded
  run of the engine and replay it in the browser; it now reads a live node, so
  the build only produces the shell and the client fills it in.

  The shell renders with a zero frame rather than by fetching the node at build
  time. Baking a snapshot in would put a number on the page that looks live and
  is as old as the last deploy, which is the failure mode the status line
  exists to prevent."
  (:require [clojure.java.io :as io]
            [clojure.string]
            [torihiki-terminal.view :as view]))

(def zero-frame
  {:height 0 :last 0 :mark 0 :oracle 0 :bids [] :asks [] :trades []
   :position {:size 0 :entry 0 :upnl 0} :equity 0 :funding 0 :resting 0
   :root (apply str (repeat 32 "0"))})

(def ^:private bundle "/js/app.js")

(defn- check-bundle!
  "Refuse to write a document that does not load the client.

  For the entire life of the 'live client' this build emitted no script tag.
  The client was written, compiled to public/js/app.js, deployed, and never
  referenced from the HTML — so the page kept the zero frame below, the status
  line read 'connecting to the node…' forever, and the whole thing looked
  exactly like a terminal whose node was slow to answer.

  Nothing catches that on its own. The build succeeds, the bundle exists on
  disk and is uploaded, the HTML is valid, and the design audit scores 100 on
  a page that does nothing. It was found by opening the deployed site in a
  real browser, which is the only place the difference is visible — so this
  assertion exists to make the next occurrence loud, and `verify.cljs` exists
  because an assertion about the HTML still cannot tell you the page WORKS."
  [html]
  (when-not (clojure.string/includes? html bundle)
    (throw (ex-info (str "build: the document does not reference " bundle
                         " — it would render as a static picture of a live page")
                    {:bundle bundle})))
  (when-not (.exists (io/file (str "public" bundle)))
    (throw (ex-info (str "build: " bundle " has not been compiled — run shadow-cljs release client")
                    {:bundle bundle}))))

(defn -main [& _]
  (let [html (view/render-page {:frames [zero-frame] :meta {:blocks 0}})]
    (check-bundle! html)
    (io/make-parents "public/index.html")
    (spit "public/index.html" html)
    (println "wrote public/index.html" (format "(%.1f KB)" (/ (count html) 1024.0))
             "-> loads" bundle)))
