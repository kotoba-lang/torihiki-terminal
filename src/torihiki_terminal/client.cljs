(ns torihiki-terminal.client
  "The live client: polls the node and re-renders the panels.

  ## It renders with the same functions the server used

  `order-book`, `trades-panel`, `chain-panel` and `ticker` are the SAME pure
  hiccup functions `render-page` calls at build time. The client turns their
  output into HTML with `kotoba-ui.core/->html` and swaps it in.

  That replaces the previous version's hand-written JavaScript, which built
  the same rows with string concatenation. Two renderers for one panel is two
  things to keep in agreement, and the one written in JavaScript could not use
  the design system's classes without repeating them by hand — which it did.

  ## What it does not do

  It does not run the engine. The node does, and this asks it what happened.
  Running the engine here as well would be a second execution of the same
  transactions, which is a genuinely useful thing (it is how a light client
  verifies) but is a different feature from a terminal, and claiming one while
  doing the other is the kind of thing this project keeps refusing to do.

  ## Failure is visible

  When the node cannot be reached the page says so rather than freezing on the
  last good frame. A terminal that silently shows stale prices is worse than
  one that admits it is disconnected: the first invites a trade."
  (:require [kotoba-ui.core :as ui]
            [torihiki-terminal.view :as view]))

(def node-url
  "Read from a data attribute the server rendered, so the endpoint is
  configuration rather than something compiled into the bundle."
  (or (some-> (.getElementById js/document "tk-root")
              (.getAttribute "data-node"))
      "https://torihiki-node.04-feasts-minded.workers.dev"))

(defn- fetch-json [path]
  (-> (js/fetch (str node-url path))
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn- swap-html! [id hiccup]
  (when-let [el (.getElementById js/document id)]
    (set! (.-innerHTML el) (ui/->html hiccup))))

(defn- set-status! [ok? text]
  (when-let [el (.getElementById js/document "tk-status")]
    (set! (.-textContent el) text)
    (set! (.-className el) (str "hig-caption2 " (if ok? "tk-dim" "tk-down")))))

(defn- frame
  "Assemble the shape the view functions expect from what the node returns.
  The node speaks its read models; the view speaks frames. Translating in one
  place keeps both of them from knowing about the other's shape."
  [head market book trades]
  {:height (:height head)
   :last (:last market)
   :mark (:mark market)
   :oracle (:oracle market)
   :bids (:bids book)
   :asks (:asks book)
   :trades (mapv (fn [t] {:level (:level t) :qty (:qty t)
                          :side (:side t) :ts (* 1000 (:h t))})
                 (:trades trades))
   :position {:size 0 :entry 0 :upnl 0}
   :equity 0
   :funding (:funding-rate market)
   :resting (:resting head)
   :root (:state-root head)})

(defn- render! [f]
  (swap-html! "tk-book-panel" (view/order-book f))
  (swap-html! "tk-trades-panel" (view/trades-panel f))
  (swap-html! "tk-chain-panel" (view/chain-panel f nil))
  (swap-html! "tk-ticker" (view/ticker-body f)))

(defn tick! []
  (-> (js/Promise.all
       #js [(fetch-json "/head") (fetch-json "/market")
            (fetch-json "/book?depth=11") (fetch-json "/trades?n=14")])
      (.then (fn [[head market book trades]]
               (render! (frame head market book trades))
               (set-status! true (str "live · block " (:height head)))))
      (.catch (fn [e]
                ;; say it, do not freeze on the last good frame
                (set-status! false "disconnected — the numbers below are stale")
                (js/console.error "torihiki-terminal:" e)))))

(defn ^:export start []
  (tick!)
  (js/setInterval tick! 1500))
