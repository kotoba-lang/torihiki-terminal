(ns torihiki-terminal.client
  "The live client: polls the node, re-renders the panels, and submits signed
  transactions.

  ## It renders with the same functions the server used

  `order-book`, `trades-panel`, `chain-panel` and `ticker` are the SAME pure
  hiccup functions `render-page` calls at build time. The client turns their
  output into HTML with `kotoba-ui.core/->html` and swaps it in.

  That replaces the previous version's hand-written JavaScript, which built
  the same rows with string concatenation. Two renderers for one panel is two
  things to keep in agreement, and the one written in JavaScript could not use
  the design system's classes without repeating them by hand — which it did.

  ## The order form used to be a picture of an order form

  Buttons, price and size fields, three flag chips and a 'Connect wallet'
  button, none of which did anything — next to a position panel whose size,
  entry and unrealised PnL were the literal zeros this file passed in. The
  page did not say any of that. A terminal that renders a position it never
  asked the node about is not incomplete, it is wrong, and the panel hardest
  to notice is the one that always shows a plausible number.

  Both are real now: the form signs and submits, and the position comes from
  `/account`.

  ## What it does not do

  It does not run the engine. The node does, and this asks it what happened.
  Running the engine here as well would be a second execution of the same
  transactions, which is a genuinely useful thing (it is how a light client
  verifies) but is a different feature from a terminal, and claiming one while
  doing the other is the kind of thing this project keeps refusing to do.

  ## Failure is visible

  When the node cannot be reached the page says so rather than freezing on the
  last good frame. A terminal that silently shows stale prices is worse than
  one that admits it is disconnected: the first invites a trade. The same
  applies to a refused transaction — the chain gives a reason, so the form
  shows the reason."
  (:require [kotoba-ui.core :as ui]
            [torihiki-terminal.keys :as tkeys]
            [torihiki-terminal.view :as view]))

(def node-url
  "Read from a data attribute the server rendered, so the endpoint is
  configuration rather than something compiled into the bundle."
  (or (some-> (.getElementById js/document "tk-root")
              (.getAttribute "data-node"))
      "https://torihiki-node.04-feasts-minded.workers.dev"))

(def ^:const market 1)

(declare tick!)

(defonce session (atom {:key nil :account nil :chain-id nil :busy false}))

(defn- fetch-json [path]
  (-> (js/fetch (str node-url path))
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn- post-json [path body]
  (-> (js/fetch (str node-url path)
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify (clj->js body))})
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn- el [id] (.getElementById js/document id))

(defn- swap-html! [id hiccup]
  (when-let [e (el id)] (set! (.-innerHTML e) (ui/->html hiccup))))

(defn- set-text! [id s]
  (when-let [e (el id)] (set! (.-textContent e) s)))

(defn- set-status! [ok? text]
  (when-let [e (el "tk-status")]
    (set! (.-textContent e) text)
    (set! (.-className e) (str "hig-caption2 " (if ok? "tk-dim" "tk-down")))))

(defn- set-order-status! [ok? text]
  (when-let [e (el "tk-order-status")]
    (set! (.-textContent e) text)
    (set! (.-className e) (str "hig-footnote " (if ok? "tk-dim" "tk-down")))))

;; ── frames ──────────────────────────────────────────────────────────────────

(defn- position-for
  "`/account` returns positions keyed by market id, and JSON turns integer keys
  into strings — so `js->clj :keywordize-keys` gives `:1`, not `1`. Looking up
  the integer alone silently found nothing, which renders as a flat account:
  the failure is invisible because 'no position' is a legitimate answer."
  [acct m]
  (let [ps (:positions acct)]
    (or (get ps (keyword (str m))) (get ps (str m)) (get ps m))))

(defn- frame
  "Assemble the shape the view functions expect from what the node returns.
  The node speaks its read models; the view speaks frames. Translating in one
  place keeps both of them from knowing about the other's shape."
  [head market* book trades acct]
  (let [pos (position-for acct market)]
    {:height (:height head)
     :last (:last market*)
     :mark (:mark market*)
     :oracle (:oracle market*)
     :bids (:bids book)
     :asks (:asks book)
     :trades (mapv (fn [t] {:level (:level t) :qty (:qty t)
                            :side (:side t) :ts (* 1000 (:h t))})
                   (:trades trades))
     ;; From /account, not from a literal. An account with nothing open
     ;; genuinely has no position, which is a different statement from "we
     ;; never asked".
     :position {:size (:size pos 0)
                :entry (:entry pos 0)
                :upnl (:unrealized pos 0)}
     :equity (:equity acct 0)
     :funding (:funding-rate market*)
     :resting (:resting head)
     :root (:state-root head)}))

(defn- render! [f]
  (swap-html! "tk-book-panel" (view/order-book f))
  (swap-html! "tk-trades-panel" (view/trades-panel f))
  (swap-html! "tk-chain-panel" (view/chain-panel f nil))
  (swap-html! "tk-positions-panel" (view/positions-panel f))
  (swap-html! "tk-ticker" (view/ticker-body f)))

(defn- render-session! [acct]
  (set-text! "tk-account" (str (:account acct)))
  (set-text! "tk-collateral" (str "$" (view/dollars (:collateral acct 0))))
  (set-text! "tk-free" (str "$" (view/dollars (:free-collateral acct 0))))
  (set-text! "tk-nonce" (str (:next-nonce acct 1))))

;; ── claiming an id ──────────────────────────────────────────────────────────

(defn- claim-account
  "Walk candidate ids until one is unclaimed or already ours.

  Bounded: an unbounded walk against an unreachable node is a page that spins
  forever with no explanation. Ten is far past what a 30-bit seed collides at,
  so exhausting it means something else is wrong and should be said."
  [k]
  (letfn [(step [n]
            (if (>= n 10)
              (js/Promise.resolve nil)
              (let [id (tkeys/seed-account (:public k) n)]
                (-> (fetch-json (str "/account?id=" id))
                    (.then (fn [a]
                             (let [bound (:bound-key a)]
                               (if (or (nil? bound) (= bound (:public k)))
                                 id
                                 (step (inc n))))))))))]
    (if-let [id (:account k)] (js/Promise.resolve id) (step 0))))

;; ── submitting ──────────────────────────────────────────────────────────────

(defn- submit!
  "Sign `tx` with the session key and post it, reporting the outcome either
  way.

  The nonce is read from the node on every submission rather than tracked
  here. A client-side counter drifts the moment a request is lost in flight or
  anything else submits for the account, and the symptom is `bad-nonce` on the
  transaction the trader is watching the price for."
  [tx]
  (let [{:keys [key account chain-id busy]} @session]
    (cond
      (not (tkeys/supported?))
      (do (set-order-status! false "This browser has no Ed25519 in WebCrypto, so nothing here can be signed.")
          (js/Promise.resolve nil))

      (or (nil? key) (nil? account) (nil? chain-id))
      (do (set-order-status! false "Still starting up — no session key yet.")
          (js/Promise.resolve nil))

      busy
      (do (set-order-status! false "One at a time: nonces are strictly sequential, so two in flight is one rejection.")
          (js/Promise.resolve nil))

      :else
      (do
        (swap! session assoc :busy true)
        (set-order-status! true "signing…")
        (-> (fetch-json (str "/account?id=" account))
            (.then (fn [a] (tkeys/sign-tx key chain-id account (:next-nonce a 1) tx)))
            (.then (fn [envelope] (post-json "/tx" envelope)))
            (.then (fn [r]
                     (if (:ok r)
                       (set-order-status! true (str "accepted at block " (:height r)))
                       ;; the chain gave a reason; reporting "failed" instead
                       ;; would throw away the only useful part of the answer
                       (set-order-status! false (str "refused: " (or (:reason r) "unknown"))))
                     (tick!)
                     r))
            (.catch (fn [e]
                      (set-order-status! false "could not reach the node")
                      (js/console.error "torihiki-terminal:" e)
                      nil))
            (.finally (fn [] (swap! session assoc :busy false))))))))

;; ── the form ────────────────────────────────────────────────────────────────

(defn- chip-el [act] (.querySelector js/document (str "[data-act='" (name act) "']")))

(defn- flag-on? [act]
  (= "1" (some-> (chip-el act) (.-dataset) (.-tkOn))))

(defn- read-int [id]
  (let [v (some-> (el id) (.-value) (.trim))]
    (when (and v (seq v) (re-matches #"\d+" v))
      (js/parseInt v 10))))

;; Mirrors torihiki.book's flag bits. Kept as three named constants rather
;; than a magic literal so a wrong one is a wrong NAME at the call site.
(def ^:const flag-post-only 1)
(def ^:const flag-ioc 2)
(def ^:const flag-reduce-only 4)

(defn- flags []
  (bit-or (if (flag-on? :flag-post) flag-post-only 0)
          (if (flag-on? :flag-ioc) flag-ioc 0)
          (if (flag-on? :flag-ro) flag-reduce-only 0)))

(defn- place! [side]
  (let [level (read-int "tk-price")
        qty (read-int "tk-size")]
    (cond
      (nil? level) (set-order-status! false "Limit price must be a whole number of ticks.")
      (nil? qty) (set-order-status! false "Size must be a whole number of lots.")
      (zero? qty) (set-order-status! false "Size must be greater than zero.")
      :else (submit! {:tx :order :market market :side side
                      :level level :qty qty :flags (flags)}))))

(defn- faucet! []
  ;; A deposit signed by the account crediting itself — which is what a deposit
  ;; is on a chain with no bridge. The engine supports naming a bridge
  ;; authority and this devnet deliberately does not set one, so the button
  ;; says "mint" rather than dressing the same act up as funding.
  (submit! {:tx :deposit :market market :amount 10000000}))

(defn- toggle-chip! [^js e]
  (let [on? (= "1" (.. e -dataset -tkOn))]
    (set! (.. e -dataset -tkOn) (if on? "0" "1"))
    (.toggle (.-classList e) "tk-chip-on" (not on?))))

(defn- wire-form!
  "One delegated listener rather than one per control: the panels are replaced
  wholesale on every tick, so anything bound to an element directly would stop
  working after the first frame."
  []
  (.addEventListener
   js/document "click"
   (fn [^js ev]
     (when-let [t (some-> (.-target ev) (.closest "[data-act]"))]
       (case (.. t -dataset -act)
         "buy" (do (.preventDefault ev) (place! 0))
         "sell" (do (.preventDefault ev) (place! 1))
         "faucet" (do (.preventDefault ev) (faucet!))
         ("flag-post" "flag-ioc" "flag-ro") (do (.preventDefault ev) (toggle-chip! t))
         nil)))))

;; ── the loop ────────────────────────────────────────────────────────────────

(defn tick! []
  (let [account (:account @session)]
    (-> (js/Promise.all
         #js [(fetch-json "/head") (fetch-json "/market")
              (fetch-json "/book?depth=11") (fetch-json "/trades?n=14")
              (if account
                (fetch-json (str "/account?id=" account))
                (js/Promise.resolve {}))])
        (.then (fn [[head market* book trades acct]]
                 (swap! session assoc :chain-id (:chain-id head))
                 (render! (frame head market* book trades acct))
                 (when account (render-session! acct))
                 (set-status! true (str "live · block " (:height head)))))
        (.catch (fn [e]
                  ;; say it, do not freeze on the last good frame
                  (set-status! false "disconnected — the numbers below are stale")
                  (js/console.error "torihiki-terminal:" e))))))

(defn ^:export start []
  (wire-form!)
  (if-not (tkeys/supported?)
    (set-text! "tk-session-note"
               "This browser has no Ed25519 in WebCrypto, so this page can read the chain but cannot sign anything for it.")
    (-> (tkeys/load-or-create!)
        (.then (fn [k]
                 (swap! session assoc :key k)
                 (claim-account k)))
        (.then (fn [id]
                 (if id
                   (do (tkeys/remember-account! (:key @session) id)
                       (swap! session assoc :account id)
                       (tick!))
                   (set-text! "tk-session-note"
                              "Could not claim an account id: every candidate is bound to another key, which should not happen and means something else is wrong."))))
        (.catch (fn [e]
                  (set-text! "tk-session-note"
                             "Could not start a session — the node did not answer.")
                  (js/console.error "torihiki-terminal:" e)))))
  (tick!)
  (js/setInterval tick! 1500))
