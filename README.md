# torihiki-terminal

The trading terminal for [`torihiki`](https://github.com/kotoba-lang/torihiki).

**Live:** https://torihiki.pages.dev — reading
[`torihiki-node`](https://github.com/kotoba-lang/torihiki-node) as it produces
blocks.

## What is real here

The book, the fills, the funding rate and the state roots are read from a live
sequencer running the real engine in a Cloudflare Worker. Nothing is recorded
and nothing is mocked. Earlier versions of this page replayed a session
generated at build time; it now polls the node.

**It is a sequencer, not a chain.** One writer decides the order; nothing
votes. The node says so in its own `/head` response and the page repeats it.

## The browser renders with the same functions the server does

`order-book`, `trades-panel`, `chain-panel` and `ticker-body` are pure hiccup
functions. `script/build.clj` calls them to render the shell; the client calls
the same ones and swaps the result in with `kotoba-ui.core/->html`.

That replaced ~90 lines of hand-written JavaScript which built the same rows
by string concatenation. Two renderers for one panel is two things to keep in
agreement, and the JavaScript one could not use the design system's classes
without repeating them by hand — which it did.

## Failure is visible

When the node is unreachable the status line says so instead of leaving the
last good frame on screen. A terminal that silently shows stale prices is
worse than one that admits it is disconnected: the first invites a trade.

## Build

```bash
npm install
npx shadow-cljs release client   # browser bundle -> public/js/app.js
clojure -M:build                 # shell         -> public/index.html
npx wrangler pages deploy public --project-name torihiki
```

## Design system

Built on the paved road (`kotoba-uiux` skill, ADR-2607122200): requires
`kotoba-ui.core` only, one theme map, layout from shell, the eleven HIG text
styles, no raw hex outside the theme. App CSS is ~30 lines, covering what a
design system has no opinion about — tabular numerals and a depth bar.

design-quality: **100.00**, no findings.

## Known couplings

`config.cljc` duplicates the node's tick and lot scale so the terminal can
format integer ticks into dollars. The node already exposes both on `/market`;
taking them from there is the right fix and is not done yet.

`appkit` is absent because it cannot be depended on: its production `:deps`
names kotoba-ui as `{:local/root "../kotoba-ui"}`, so any consumer depending
on it through git cannot build a classpath.
