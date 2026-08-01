# torihiki-terminal

The trading terminal for [`torihiki`](https://github.com/kotoba-lang/torihiki) —
an open reimplementation of the exchange state machine Hyperliquid keeps closed.

**Live:** *(see the deploy URL in the superproject ADR)*

## What is real here

Every number on the page came out of the real engine. The order book is
`torihiki.book`'s matching under price-time priority, the position and its
unrealized PnL are `torihiki.clearing`, the funding rate is
`torihiki.funding`'s premium index, and the state roots are SHA-256 over
`torihiki.state`'s canonical encoding. `script/build.clj` drives 240 blocks
through the engine at build time and renders the result.

**It is a recording, not a live venue**, and the page says so. The engine
*does* run in a browser — the same ClojureScript sources reproduce a JVM
validator's state root byte for byte, which `torihiki`'s `:parity` check
proves — but executing it in the visitor's tab is a separate build that has
not been done. Stating which one you are looking at costs one sentence and
buys the difference between a demo and a claim.

## Build

```bash
clojure -M:build     # engine → session → public/index.html
```

## Design system

Built on the paved road (`kotoba-uiux` skill, ADR-2607122200): requires
`kotoba-ui.core` only, one theme map, layout from shell, the eleven HIG text
styles, no raw hex outside the theme. The app stylesheet is ~30 lines and
covers what a design system has no opinion about — tabular numerals and a
depth bar.

Scored with `kotoba-lang/design-quality`:

```
design-quality audit — 1 page(s)
  100.00  public/index.html
findings (headroom-first):
  (none — converged)
```

`appkit` would be the right platform layer for a dense desktop terminal and is
deliberately absent: its production `:deps` names kotoba-ui as
`{:local/root "../kotoba-ui"}`, so any consumer depending on appkit through
git cannot build a classpath. The repo already has a `:local` alias holding
that override, which is where a sibling path belongs.
