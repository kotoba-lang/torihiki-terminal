(ns torihiki-terminal.session
  "A recorded trading session, produced by driving the REAL engine.

  Nothing here is mock data. Every price on the terminal came out of
  `torihiki.book`'s matching, every position out of `torihiki.clearing`, every
  funding rate out of `torihiki.funding`'s premium index, and every state root
  out of `torihiki.state`. What the browser does is replay frames the engine
  produced; it does not execute the engine itself. That distinction is stated
  on the page, because 'a real engine ran this' and 'the engine is running in
  your tab' are different claims and only the first one is true today.

  Determinism matters even for a demo: the session comes from a fixed LCG, so
  the published page is reproducible from this source and a reviewer can check
  that the numbers on it are the numbers the engine emits."
  (:require [torihiki.book :as bk]
            [torihiki.clearing :as cl]
            [torihiki.funding :as fnd]
            [torihiki.state :as st]))

;; ── market ──────────────────────────────────────────────────────────────────
;;
;; Prices are integer ticks. One tick is $0.10 and one lot is 0.001 BTC, which
;; puts a $68,000 BTC at level 680000 and keeps every notional far inside the
;; i53 domain (see torihiki.fixed).

(def ^:const tick-usd-cents 10)
(def ^:const lots-per-unit 1000)
(def ^:const start-level 680000)

(def market
  (assoc (cl/market {:id 1 :max-leverage 40 :tick tick-usd-cents :lot 1})
         :taker-fee-rate 350000     ; 3.5 bp
         :maker-fee-rate 100000))   ; 1.0 bp

(def ^:const trader 1)              ; the account the terminal is logged in as
(def ^:const mm-a 2)
(def ^:const mm-b 3)
(def ^:const taker 4)

(defn- lcg [seed]
  (mod (+ (* seed 1103515245) 12345) 2147483648))

;; ── frames ──────────────────────────────────────────────────────────────────

(defn- depth
  "Top `n` occupied levels on `side`, nearest the touch first, with a running
  cumulative size — the shape an order-book panel actually renders."
  [book side n]
  (loop [level (bk/best book side) i 0 cum 0 out []]
    (if (or (neg? level) (>= i n))
      out
      (let [q (bk/level-qty book side level)
            cum (+ cum q)]
        (recur (bk/next-occupied book side level) (inc i) cum
               (conj out {:level level :qty q :cum cum}))))))

(defn- frame
  [ex trades]
  (let [book (get-in ex [:books 1])
        pos (cl/position (:clearing ex) trader 1)
        mark (get-in ex [:marks 1] start-level)
        oracle (get-in ex [:oracle 1] mark)]
    {:height (:height ex)
     :ts (:ts ex)
     ;; `:last` is the print, `:mark` is what margin is measured against. They
     ;; are shown separately because they ARE separate — see torihiki.mark.
     :last (get-in ex [:last 1] mark)
     :mark mark
     :oracle oracle
     :bids (depth book bk/bid 11)
     :asks (depth book bk/ask 11)
     :trades (vec (take 14 trades))
     :position {:size (:size pos)
                :entry (cl/entry-price pos)
                :upnl (cl/unrealized pos mark)}
     :equity (cl/equity (:clearing ex) trader {1 mark})
     :funding (get-in ex [:last-funding-rate 1] 0)
     :resting (bk/resting-count book)
     :root (st/state-root ex)}))

(defn- quote-block
  "One block of two-sided market-making around `mid`, plus some aggression."
  [seed height mid]
  (let [s1 (lcg seed)
        s2 (lcg s1)
        s3 (lcg s2)
        aggressive? (< (mod s3 100) 62)
        buy? (even? (mod s2 7))
        txs (concat
             ;; both market makers refresh a ladder each side
             (for [i (range 9)
                   [acct side sign] [[mm-a bk/bid -1] [mm-b bk/ask 1]]]
               {:tx :order :account acct :market 1 :side side
                :level (+ mid (* sign (+ 1 (* i 3) (mod (+ s1 i) 4))))
                :qty (+ 20 (mod (+ s2 (* i 13)) 180))})
             (when aggressive?
               [{:tx :order :account taker :market 1
                 :side (if buy? bk/bid bk/ask)
                 :level (+ mid (if buy? 40 -40))
                 :qty (+ 15 (mod s3 220))
                 :flags bk/flag-ioc}])
             ;; the logged-in trader works a modest position
             (when (zero? (mod height 6))
               [{:tx :order :account trader :market 1
                 :side (if (even? (mod s1 5)) bk/bid bk/ask)
                 :level (+ mid (if (even? (mod s1 5)) 30 -30))
                 :qty (+ 8 (mod s2 40))
                 :flags bk/flag-ioc}])
             [{:tx :oracle :market 1 :price mid}
              {:tx :funding-sample :market 1}]
             (when (zero? (mod height 20))
               [{:tx :funding-settle :market 1}]))]
    [s3 (vec (remove nil? txs))]))

(defn generate
  "Drive the engine for `n` blocks and return `{:frames [...] :meta {...}}`."
  [n]
  (let [ex0 (-> (st/new-exchange
                 {:market market
                  :book-opts {:n-levels 1048576 :cap 262144 :ev-cap 65536}})
                (st/apply-tx {:tx :deposit :account trader :amount 250000000})
                (st/apply-tx {:tx :deposit :account mm-a :amount 900000000})
                (st/apply-tx {:tx :deposit :account mm-b :amount 900000000})
                (st/apply-tx {:tx :deposit :account taker :amount 900000000}))]
    (loop [i 1 ex ex0 seed 987654321 mid start-level trades () frames []]
      (if (> i n)
        {:frames frames
         :meta {:blocks n
                :tick-usd-cents tick-usd-cents
                :lots-per-unit lots-per-unit
                :taker-fee-rate (:taker-fee-rate market)
                :maker-fee-rate (:maker-fee-rate market)
                :max-leverage (:max-leverage market)
                :maintenance-margin-rate (:maintenance-margin-rate market)
                :final-root (st/state-root ex)}}
        (let [[seed' txs] (quote-block seed i mid)
              ex' (st/apply-block ex {:height i :ts (* i 1000) :txs txs})
              book (get-in ex' [:books 1])
              fills (bk/fills book)
              trades' (into (list)
                            (concat (map (fn [f]
                                           {:level (:level f) :qty (:qty f)
                                            :side (:taker-side f) :ts (* i 1000)})
                                         (reverse fills))
                                    trades))
              ;; the mid follows what actually traded, not a synthetic walk
              mid' (let [b (bk/best book bk/bid) a (bk/best book bk/ask)]
                     (cond (and (pos? b) (pos? a)) (quot (+ a b) 2)
                           (pos? (bk/last-price book)) (bk/last-price book)
                           :else mid))]
          (recur (inc i) ex' seed' mid' trades'
                 (conj frames (frame ex' trades'))))))))
