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
(def ^:const node-url "https://torihiki-node.04-feasts-minded.workers.dev")
