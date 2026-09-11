(ns torihiki-terminal.config
  "The scale the node's market uses, and where the node is.

  These are duplicated from `torihiki-node.worker`'s market definition, which
  is a real coupling and is written down rather than hidden: the terminal
  formats integer ticks into dollars, so it has to know what a tick is worth.
  The node exposes `:tick` and `:lot` on `/market`, and the right fix is for
  the client to take them from there rather than from a constant — a follow-up
  that stops mattering only once it is done.")

(def ^:const tick-usd-cents 10)
(def ^:const lots-per-unit 1000)
(def ^:const market-id 1)
(def ^:const max-leverage 40)
(def ^:const node-url
  "The BFT chain, not the single sequencer.

  `torihiki-node` is one Durable Object deciding the order by itself and
  saying so at `/head`. `torihiki-validator` is four of them running
  `engi.replica` with the same engine as their state machine. The terminal
  pointed at the first one for as long as the second one did not work; two
  chains where there should be one is a question about which is real, and the
  answer should not be whichever the page happens to fetch."
  "https://torihiki-validator.04-feasts-minded.workers.dev")

(def witnesses ["w1" "w2" "w3" "w4"])
