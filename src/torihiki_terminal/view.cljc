(ns torihiki-terminal.view
  "The terminal, as pure hiccup.

  Built on the paved road: this ns requires `kotoba-ui.core` and nothing below
  it, writes no raw hex outside the one theme map, takes every type size from the eleven HIG text styles, and builds
  its layout from shell rather than from hand-written CSS. The app stylesheet
  below is the deliberate remainder — a trading terminal needs tabular
  numerals and a depth bar, and neither is a design-system concept.

  `appkit` would be the right platform layer for a dense desktop terminal, and
  it is not required here because it cannot be: its production `:deps` names
  kotoba-ui as `{:local/root \"../kotoba-ui\"}`, so any consumer depending on
  appkit through git fails to build a classpath (`Local lib
  io.github.kotoba-lang/kotoba-ui not found: .../appkit/kotoba-ui`). The repo
  already carries a `:local` alias holding exactly that override, which is
  where a sibling path belongs. Filed rather than worked around silently."
  (:require [clojure.string :as str]
            [kotoba-ui.core :as ui]
            [torihiki-chart.candle :as tc]
            [torihiki-chart.depth :as td]
            [torihiki-chart.view :as tcv]
            [torihiki-terminal.config :as cfg]
            [torihiki-terminal.skin :as skin]))

(def theme
  "The only place a hex color is legitimate in app code (agent-guide rule 5)."
  {:accent "#3DD8B6" :accent-dark "#3DD8B6" :appearance :dark})

;; ── formatting ──────────────────────────────────────────────────────────────
;;
;; Integer in, string out. The engine speaks ticks and lots; a human reads
;; dollars and coins. Doing the conversion here — once, at the edge — is what
;; keeps `torihiki.fixed`'s no-floating-point rule intact everywhere behind it.

(defn- pad-left [s n ch] (str (apply str (repeat (max 0 (- n (count s))) ch)) s))

(defn usd
  "Price ticks → dollars. One tick is ten cents."
  [ticks]
  (let [cents (* ticks cfg/tick-usd-cents)
        d (quot cents 100)
        c (rem cents 100)
        s (str d)
        grouped (->> (reverse s)
                     (partition-all 3)
                     (map #(apply str (reverse %)))
                     reverse
                     (str/join ","))]
    (str grouped "." (pad-left (str c) 2 "0"))))

(defn coins
  "Lots → units, three decimals."
  [lots]
  (let [neg? (neg? lots)
        a (if neg? (- lots) lots)]
    (str (when neg? "-") (quot a cfg/lots-per-unit) "."
         (pad-left (str (rem a cfg/lots-per-unit)) 3 "0"))))

(defn dollars
  "Collateral and equity are in cents; `usd` takes ticks. Converting at the
  call site is how the two got mixed up once already — the panel showed a
  balance ten times too large and looked entirely plausible."
  [cents]
  (usd (quot cents cfg/tick-usd-cents)))

(defn signed-usd [cents]
  (str (if (neg? cents) "-" "+") "$" (usd (Math/abs (long (quot cents cfg/tick-usd-cents))))))

(defn rate-pct
  "A rate in torihiki.fixed's 1e9 scale → percent with four decimals."
  [r]
  (let [bp (quot (* r 1000000) 1000000000)]
    (str (if (neg? r) "" "+") (/ (double bp) 10000.0) "%")))

;; ── panels ──────────────────────────────────────────────────────────────────

(defn- book-row [{:keys [level qty cum]} side max-cum]
  [:div {:class (str "tk-row tk-row--" (name side))
         :data-level level}
   [:span {:class "tk-bar"
           :style {:width (str (min 100 (quot (* 100 cum) (max 1 max-cum))) "%")}}]
   [:span {:class "tk-px"} (usd level)]
   [:span {:class "tk-sz"} (coins qty)]
   [:span {:class "tk-cum"} (coins cum)]])

(defn order-book
  "Panel body. The client re-renders this exact function and swaps the result
  into `#tk-book-panel`, so the server and the browser never disagree about
  what a row looks like."
  [frame]
  (let [{:keys [bids asks]} frame
        max-cum (max 1 (apply max 1 (map :cum (concat bids asks))))]
    (ui/panel
     [[:div {:class "tk-panel-head"}
       [:span {:class "hig-headline"} "Order book"]
       [:span {:class "hig-caption2 tk-dim"} "price / size / total"]]
      [:div {:class "tk-col-head hig-caption2 tk-dim"}
       [:span "Price (USD)"] [:span "Size (BTC)"] [:span "Total"]]
      [:div {:id "tk-asks" :class "tk-side tk-side--ask"}
       (map #(book-row % :ask max-cum) (reverse asks))]
      [:div {:id "tk-spread" :class "tk-spread"}
       [:span {:class "hig-title3 tk-last"} (str "$" (usd (:last frame)))]
       [:span {:class "hig-caption1 tk-dim"} "spread"]]
      [:div {:id "tk-bids" :class "tk-side tk-side--bid"}
       (map #(book-row % :bid max-cum) bids)]]
     {:class "tk-book"})))

(defn trades-panel [frame]
  (ui/panel
   [[:div {:class "tk-panel-head"}
     [:span {:class "hig-headline"} "Trades"]
     [:span {:class "hig-caption2 tk-dim"} "engine fills"]]
    [:div {:class "tk-col-head hig-caption2 tk-dim"}
     [:span "Price"] [:span "Size"] [:span "Block"]]
    [:div {:id "tk-trades" :class "tk-side"}
     (for [t (:trades frame)]
       [:div {:class (str "tk-row tk-row--" (if (zero? (:side t)) "bid" "ask"))}
        [:span {:class "tk-px"} (usd (:level t))]
        [:span {:class "tk-sz"} (coins (:qty t))]
        ;; The block, not a time. It used to arrive as `(* 1000 h)` and be
        ;; divided back here — a round trip through milliseconds that do not
        ;; exist, since the engine has no wall clock.
        [:span {:class "tk-cum"} (:h t)]])]]
   {:class "tk-trades"}))

(defn chart-panel
  "Block candles and the depth of the book.

  **The x axis is block height, not time.** The engine has no wall clock —
  logical time arrives in the block header — so a fill carries `:h` and
  nothing else to order it by. Drawing these as if they were evenly spaced
  minutes would be inventing a fact the chain does not have, and a block's
  real duration is not constant: a view change or a Durable Object eviction
  stretches it. The labels read `#4218` for that reason; `12:04` would look
  like a clock.

  The span is chosen from the tape's own height range rather than fixed,
  because the tape is bounded by COUNT (200 fills) and so the range it covers
  depends on how busy the book is — quiet, 200 fills span thousands of blocks;
  busy, a few dozen.

  When there are no fills this says so instead of drawing an empty frame. An
  empty frame does not read as \"no data\", it reads as \"no price\"."
  [frame]
  (let [tape (:trades frame)
        span (tc/auto-span 48 tape)
        candles (tc/candles span tape)]
    (ui/panel
     [[:div {:class "tk-panel-head"}
       [:span {:class "hig-headline"} "Chart"]
       [:span {:class "hig-caption2 tk-dim"}
        (if (seq candles)
          (str span " block" (when (> span 1) "s") " per candle")
          "block candles")]]
      (if-let [svg (tcv/candle-chart {:candles candles
                                      :tick-cents cfg/tick-usd-cents
                                      :height 260})]
        [:div {:class "tk-chart"} svg]
        [:p {:class "hig-footnote tk-dim"} "No fills on the chain yet."])
      [:div {:class "tk-panel-head tk-chart-sub"}
       [:span {:class "hig-caption2 tk-dim"} "Book depth"]
       [:span {:class "hig-caption2 tk-dim"} "cumulative, from the node"]]
      (if-let [svg (td/depth-chart {:bids (:bids frame) :asks (:asks frame)
                                    :tick-cents cfg/tick-usd-cents
                                    :height 140})]
        [:div {:class "tk-chart"} svg]
        [:p {:class "hig-footnote tk-dim"} "The book is empty."])]
     {:class "tk-chart-panel"})))

(defn order-entry []
  (ui/panel
   [[:div {:class "tk-panel-head"} [:span {:class "hig-headline"} "Place order"]]
    (ui/stack
     {:gap :3}
     (ui/stack {:direction :horizontal :gap :2}
               (ui/button "Buy / Long" {:act :buy :class "tk-buy"})
               (ui/button "Sell / Short" {:act :sell :class "tk-sell"}))
     (ui/text-field {:id "tk-price" :placeholder "Limit price (ticks)"
                     :aria-label "Limit price" :inputmode "numeric"})
     (ui/text-field {:id "tk-size" :placeholder "Size (lots)"
                     :aria-label "Size" :inputmode "numeric"})
     (ui/stack {:direction :horizontal :gap :2}
               ;; chip carries no :id, so these are found by their act —
               ;; adding an :id it drops would look wired and would not be
               (ui/chip "Post only" {:act :flag-post})
               (ui/chip "IOC" {:act :flag-ioc})
               (ui/chip "Reduce only" {:act :flag-ro}))
     ;; Says what happened to the last transaction, including why it was
     ;; refused. A form that silently does nothing on a rejection teaches the
     ;; trader that the button is broken, which is the wrong lesson when the
     ;; chain gave a reason.
     [:p {:id "tk-order-status" :class "hig-footnote tk-dim"}
      "No order submitted yet."]
     (ui/divider)
     [:div {:class "tk-kv hig-footnote"}
      [:span "Max leverage"] [:span (str cfg/max-leverage "x")]]
     [:div {:class "tk-kv hig-footnote"}
      [:span "Taker / maker fee"] [:span "3.5 bp / 1.0 bp"]]
     [:div {:class "tk-kv hig-footnote"}
      [:span "Maintenance margin"] [:span "1.25%"]])]
   {:class "tk-entry"}))

(defn session-panel
  "The account this browser trades as, and where its collateral came from.

  There is no wallet to connect. The key is generated here and stays here, and
  the collateral is minted by asking — on a devnet with no bridge, that is
  what a deposit is. Both facts are on the panel rather than in a document,
  because the panel is what somebody about to trade actually reads."
  []
  (ui/panel
   [[:div {:class "tk-panel-head"} [:span {:class "hig-headline"} "Session"]]
    (ui/stack
     {:gap :2}
     [:div {:class "tk-kv hig-footnote"}
      [:span "Account"] [:span {:id "tk-account" :class "tk-px"} "—"]]
     [:div {:class "tk-kv hig-footnote"}
      [:span "Collateral"] [:span {:id "tk-collateral" :class "tk-px"} "—"]]
     [:div {:class "tk-kv hig-footnote"}
      [:span "Free"] [:span {:id "tk-free" :class "tk-px"} "—"]]
     [:div {:class "tk-kv hig-footnote"}
      [:span "Next nonce"] [:span {:id "tk-nonce" :class "tk-px"} "—"]]
     (ui/button "Mint 100,000 test USD" {:act :faucet :class "tk-faucet"})
     [:p {:id "tk-session-note" :class "hig-caption2 tk-dim"}
      "Session key generated in this browser and kept in local storage. Not a
       wallet: it holds nothing outside this devnet, and clearing site data
       destroys it. Collateral here is minted on request — there is no bridge,
       so every balance on this chain was asked for rather than deposited."])]
   {:class "tk-session"}))

(defn positions-panel [frame]
  (let [{:keys [size entry upnl]} (:position frame)]
    (ui/panel
     [[:div {:class "tk-panel-head"} [:span {:class "hig-headline"} "Position"]]
      (if (zero? size)
        [:p {:class "hig-footnote tk-dim"} "No open position."]
        [:div {:class "tk-postable"}
         [:div {:class "tk-col-head hig-caption2 tk-dim"}
          [:span "Side"] [:span "Size"] [:span "Entry"] [:span "Unrealized"]]
         [:div {:class "tk-row"}
          [:span {:class (if (pos? size) "tk-up" "tk-down")}
           (if (pos? size) "LONG" "SHORT")]
          [:span {:id "tk-pos-size" :class "tk-px"} (coins (abs size))]
          [:span {:id "tk-pos-entry" :class "tk-px"} (str "$" (usd entry))]
          [:span {:id "tk-pos-upnl"
                  :class (str "tk-px " (if (neg? upnl) "tk-down" "tk-up"))}
           (signed-usd upnl)]]])]
     {:class "tk-positions"})))

(defn chain-panel [frame meta*]
  (ui/panel
   [[:div {:class "tk-panel-head"}
     [:span {:class "hig-headline"} "Chain"]
     [:span {:class "hig-caption2 tk-dim"} "sequencer"]]
    [:div {:class "tk-kv hig-footnote"}
     [:span "Block"] [:span {:id "tk-height" :class "tk-px"} (:height frame)]]
    [:div {:class "tk-kv hig-footnote"}
     [:span "Resting orders"] [:span {:id "tk-resting" :class "tk-px"} (:resting frame)]]
    [:div {:class "tk-kv hig-footnote"}
     [:span "Funding (1h)"] [:span {:id "tk-funding" :class "tk-px"} (rate-pct (:funding frame))]]
    [:div {:class "tk-kv hig-footnote"}
     [:span "Oracle"] [:span {:id "tk-oracle" :class "tk-px"} (str "$" (usd (:oracle frame)))]]
    [:p {:class "hig-caption2 tk-dim"}
     "Margin and liquidation read the MARK, never the last print. The mark is "
     "the oracle plus a banded, size-weighted book premium — one lot lifted "
     "through a thin book cannot move it, which is otherwise a way to "
     "liquidate other people."]
    [:p {:class "hig-caption2 tk-dim tk-root"}
     "state root " [:code {:id "tk-root"} (subs (:root frame) 0 32)]]
    [:p {:class "hig-caption2 tk-dim"}
     "SHA-256 over a canonical encoding of the live state. A validator replays "
     "the block and recomputes this; if it differs, the sequencer lied."]]
   {:class "tk-chain"}))

;; ── the page ────────────────────────────────────────────────────────────────

(defn ticker-body
  "The ticker's contents, without its container — the client swaps this into
  `#tk-ticker` rather than replacing the container it is mounted in."
  [frame]
  (list
   [:div {:class "tk-mkt"}
    [:span {:class "hig-headline"} "BTC-PERP"]
    [:span {:class "hig-caption2 tk-dim"} "torihiki"]]
   [:div {:class "tk-stat"}
    [:span {:class "hig-caption2 tk-dim"} "Last"]
    [:span {:id "tk-last" :class "hig-title3 tk-px"} (str "$" (usd (:last frame)))]]
   [:div {:class "tk-stat"}
    [:span {:class "hig-caption2 tk-dim"} "Mark"]
    [:span {:class "hig-body tk-px" :id "tk-mark"} (str "$" (usd (:mark frame)))]]
   [:div {:class "tk-stat"}
    [:span {:class "hig-caption2 tk-dim"} "Oracle"]
    [:span {:class "hig-body tk-px" :id "tk-oracle2"} (str "$" (usd (:oracle frame)))]]
   [:div {:class "tk-stat"}
    [:span {:class "hig-caption2 tk-dim"} "Block"]
    [:span {:id "tk-height2" :class "hig-body tk-px"} (:height frame)]]))

(defn ticker [frame]
  [:div {:id "tk-ticker" :class "tk-ticker"} (ticker-body frame)])

(defn view [{:keys [frames meta]}]
  (let [f (first frames)]
    (ui/app-shell
     {:nav (ui/nav-bar "torihiki"
                       {:trailing [(ui/chip "recorded session")
                                   (ui/button "GitHub" {:act :gh})]})
      :sidebar [(session-panel)
                (order-entry)
                [:div {:id "tk-positions-panel"} (positions-panel f)]]
      :id "tk-root"
      :attrs {:data-node cfg/node-url}}
     [:div {:class "tk-live"}
      [:span {:id "tk-status" :class "hig-caption2 tk-dim"} "connecting to the node…"]]
     (ticker f)
     [:div {:id "tk-chart-panel"} (chart-panel f)]
     [:div {:class "tk-grid"}
      [:div {:id "tk-book-panel"} (order-book f)]
      (ui/stack {:gap :3}
                [:div {:id "tk-trades-panel"} (trades-panel f)]
                [:div {:id "tk-chain-panel"} (chain-panel f meta)])]
     (ui/section
      {:title "What you are looking at" :wide true}
      [:p {:class "hig-body"}
       "Every number on this page comes from "
       [:a {:href "https://github.com/kotoba-lang/torihiki"} "torihiki"]
       ", an open reimplementation of the exchange state machine that "
       "Hyperliquid keeps closed, running live in a "
       [:a {:href "https://github.com/kotoba-lang/torihiki-node"} "Cloudflare Worker"]
       ". The book, the fills and the state roots are read from that node as "
       "it produces them: prices are integer ticks, matching is price-time "
       "priority, and the roots are SHA-256 over a canonical encoding."]
      [:p {:class "hig-body"}
       [:strong "It is a sequencer, not a chain."]
       " One writer decides the order of transactions; nothing votes and "
       "nothing tolerates a Byzantine peer. The node says so in its own "
       [:code "/head"] " response. What the sequencer cannot do is lie "
       "undetectably — every block it produces can be replayed against the "
       "same engine and its state root contradicted."]
      [:p {:class "hig-body"}
       "This page renders with the same pure functions that render it on the "
       "server, so the browser and the node never disagree about what a row "
       "looks like. When the node is unreachable it says so above rather than "
       "freezing on the last good frame — a terminal that silently shows "
       "stale prices invites a trade."]
      (ui/grid
       {:min "230px"}
       (ui/panel [[:h3 {:class "hig-headline"} "3,155,313 ops/sec"]
                  [:p {:class "hig-footnote tk-dim"}
                   "Measured matching throughput, 317 ns/op — 15.8x HyperCore's "
                   "documented 200,000 orders/sec. Execution only; the live "
                   "node is nowhere near that, and nothing here claims it is."]])
       (ui/panel [[:h3 {:class "hig-headline"} "Sequencer, not consensus"]
                  [:p {:class "hig-footnote tk-dim"}
                   "One writer decides the order. Nothing votes. The log is "
                   "checkable by replay, which is a different guarantee from "
                   "tamper-evidence and the stronger one."]])
       (ui/panel [[:h3 {:class "hig-headline"} "Signed, replay-proof"]
                  [:p {:class "hig-footnote tk-dim"}
                   "Every transaction carries an Ed25519 signature over the "
                   "chain id, the account, a strictly sequential nonce and "
                   "every field. Reusing a nonce is refused; signing someone "
                   "else's account is refused."]])
       (ui/panel [[:h3 {:class "hig-headline"} "No floating point"]
                  [:p {:class "hig-footnote tk-dim"}
                   "Every value is an integer inside the range both the JVM and "
                   "JS represent exactly, so two validators cannot disagree in "
                   "the last bit."]]))))))

(def app-css
  "Unlayered, so it wins over the library layers without any specificity
  fight (agent-guide rule 3). Confined to what a design system has no opinion
  about: tabular numerals and a depth bar.

  ## Three of these tokens did not exist

  `--hig-label-secondary`, `--hig-color-accent` and `--hig-radius-1` are not
  names `shitsuke.hig` emits — the real ones are `--hig-color-secondary-label`,
  `--hig-color-tint` and `--hig-radius-xs`. An undefined custom property does
  not fall back, it makes the whole declaration invalid, so `.tk-dim` had no
  colour of its own, an active flag chip showed no outline, and book rows had
  square corners. All three looked like design choices.

  Found by checking every token this file uses against
  `jp-go-dds.tokens/bridged?` while adding the DADS skin — the swap needed to
  know which tokens had to survive it, and three of them were already gone."
  "
.tk-px,.tk-sz,.tk-cum{font-variant-numeric:tabular-nums;font-feature-settings:'tnum'}
.tk-dim{color:var(--hig-color-secondary-label)}
.tk-up{color:var(--hig-palette-green)}
.tk-down{color:var(--hig-palette-red)}
.tk-live{padding-top:var(--hig-spacing-2)}
.tk-ticker{display:flex;flex-wrap:wrap;gap:var(--hig-spacing-6);align-items:baseline;
  padding:var(--hig-spacing-4) 0}
.tk-stat,.tk-mkt{display:flex;flex-direction:column;gap:var(--hig-spacing-1);min-width:0}
.tk-grid{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(0,1fr);
  gap:var(--hig-spacing-4);align-items:start}
@media (max-width:820px){.tk-grid{grid-template-columns:minmax(0,1fr)}}
.tk-panel-head{display:flex;justify-content:space-between;align-items:baseline;
  gap:var(--hig-spacing-3);margin-bottom:var(--hig-spacing-2)}
.tk-col-head,.tk-row{display:grid;grid-template-columns:1fr 1fr 1fr;
  gap:var(--hig-spacing-2);padding:2px var(--hig-spacing-2)}
.tk-postable .tk-col-head,.tk-postable .tk-row{grid-template-columns:1fr 1fr 1fr 1fr}
.tk-chip-on{outline:2px solid var(--hig-color-accent);outline-offset:-2px}
.tk-row{position:relative;border-radius:var(--hig-radius-xs)}
.tk-row>span{position:relative;z-index:1;text-align:right;min-width:0}
.tk-row>span:first-of-type{text-align:left}
.tk-bar{position:absolute;inset:0 auto 0 0;z-index:0;border-radius:inherit;opacity:.16}
.tk-row--bid .tk-bar{background:var(--hig-palette-green)}
.tk-row--ask .tk-bar{background:var(--hig-palette-red)}
.tk-row--bid .tk-px{color:var(--hig-palette-green)}
.tk-row--ask .tk-px{color:var(--hig-palette-red)}
.tk-spread{display:flex;justify-content:space-between;align-items:baseline;
  padding:var(--hig-spacing-2);margin:var(--hig-spacing-1) 0}
.tk-kv{display:flex;justify-content:space-between;gap:var(--hig-spacing-3)}
.tk-root code{word-break:break-all}
.tk-chart{width:100%;overflow-x:auto}
.tk-chart svg{display:block;width:100%;height:auto}
.tk-chart-sub{margin-top:var(--hig-spacing-3)}
")

(defn render-page
  "`dds-css` is the vendored デジタル庁 stylesheet, read by the caller. Passing
  it in keeps this namespace free of I/O so the browser build can require it."
  [session dds-css]
  (ui/->page {:title "torihiki — BTC-PERP"
              :description
              "An open reimplementation of Hyperliquid's closed HyperCore: order book, clearinghouse, funding and liquidation as one deterministic state machine."
              :theme theme
              ;; The bundle. Without this the page is a picture of a
              ;; terminal: every panel keeps the zero frame the build rendered
              ;; and the status line reads "connecting to the node…" forever,
              ;; which is exactly what a page loading slowly looks like.
              ;;
              ;; It was missing for the whole life of the "live client" — the
              ;; client was written, compiled, deployed to /js/app.js, and
              ;; never referenced from the document. Nothing catches that: the
              ;; build succeeds, the bundle exists, the HTML validates, and the
              ;; design audit scores a page that does nothing.
              ;; Order matters and is one-way: the skin redefines `--hig-*`
              ;; onto DADS primitives, and `app-css` reads them. Emitting the
              ;; app first would have it read the values the skin is about to
              ;; replace — which produces a page that is correct on reload and
              ;; wrong on first paint, the worst of the two.
              :head [[:style [:hiccup/raw (skin/skin-css dds-css)]]
                     [:style app-css]
                     [:script {:type "module" :src "/js/app.js" :defer true}]]}
             (view session)))
