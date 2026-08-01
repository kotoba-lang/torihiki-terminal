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
            [torihiki-terminal.view :as view]))

(def zero-frame
  {:height 0 :last 0 :mark 0 :oracle 0 :bids [] :asks [] :trades []
   :position {:size 0 :entry 0 :upnl 0} :equity 0 :funding 0 :resting 0
   :root (apply str (repeat 32 "0"))})

(defn -main [& _]
  (let [html (view/render-page {:frames [zero-frame] :meta {:blocks 0}})]
    (io/make-parents "public/index.html")
    (spit "public/index.html" html)
    (println "wrote public/index.html" (format "(%.1f KB)" (/ (count html) 1024.0)))))
