# torihiki-terminal

The trading terminal for [`torihiki`](https://github.com/kotoba-lang/torihiki).

**Live:** https://torihiki.pages.dev — reading
[`torihiki-node`](https://github.com/kotoba-lang/torihiki-node) as it produces
blocks.

## The page was a picture of itself

For the whole life of the "live client", this build emitted **no script tag**.
`client.cljs` was written, compiled to `public/js/app.js`, uploaded on every
deploy, and never referenced from the document.

So the deployed page held the zero frame the build rendered, and the status
line read `connecting to the node…` forever — which is indistinguishable from
a terminal whose node is slow to answer. Nothing looked wrong.

Everything else was green the entire time. The build succeeded. The bundle
existed and shipped. The HTML was valid. The design audit scored **100.00** on
a page that did nothing. The engine passed 140 tests. The previous commit here
described a client that polls and re-renders, and that client had never
executed in production.

It was found by opening the site in a real browser, which is the only place
the difference is visible.

Two things changed so it cannot happen quietly again:

- **`script/build.cljk` refuses to write a document that does not reference the
  bundle**, or when the bundle has not been compiled. That would have caught
  this exact bug.
- **`script/verify.cljk` clicks the deployed page** — waits for the session
  panel to fill, clicks the faucet, types a price and size, toggles a chip,
  clicks Buy, and asserts the chain moved. It exits non-zero when it does not,
  because a verification that always passes is the same kind of object as a
  page that always renders.

  ```
  NODE_PATH=<root>/node_modules kbb --backend sci script/verify.cljk
  TK_BASE=http://127.0.0.1:8899 kbb --backend sci script/verify.cljk
  ```

  Against production: `live · block 35`, faucet accepted at 36, chip toggled,
  buy accepted at 37, nonce 1 → 3.

An assertion about the HTML still cannot tell you the page works. Only
clicking it can, so both exist.

## What is real here

The book, the fills, the funding rate and the state roots are read from a live
sequencer running the real engine in a Cloudflare Worker. Nothing is recorded
and nothing is mocked. Earlier versions of this page replayed a session
generated at build time; it now polls the node.

**It is a sequencer, not a chain.** One writer decides the order; nothing
votes. The node says so in its own `/head` response and the page repeats it.

## The browser renders with the same functions the server does

`order-book`, `trades-panel`, `chain-panel` and `ticker-body` are pure hiccup
functions. `script/build.cljk` calls them to render the shell; the client calls
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
script/build_js.sh           # browser bundle -> public/js/app.js
kbb -M:build                 # shell         -> public/index.html
```

`public/` is static files; serve it from any host.

### What a deployment pins (`TK_*`)

The shell build reads the deployment's trust root from the environment and
renders it into the page (`torihiki-terminal.deploy`):

| env | |
|---|---|
| `TK_NODES` | `https://n1.example,https://n2.example:8443,...` — the standalone nodes the page reads and compares. Default: four local nodes. |
| `TK_SET` | `w1=<base64 spki>,w2=...` — the validator set. A balance shows as **proved** only when more than 2/3 of it signed the root. |
| `TK_EPOCH` | the engine epoch of that set (required with `TK_SET`) |
| `TK_CHAIN` | the chain id that set signs for (required with `TK_SET`); the page signs and verifies for this chain only, whatever the nodes report |

```bash
TK_NODES=https://n1.example,https://n2.example,https://n3.example,https://n4.example \
TK_SET="$(curl -s https://n1.example/duties | jq -r '.["segment-keys"] | to_entries | map("\(.key)=\(.value)") | join(",")')" \
TK_EPOCH=0 TK_CHAIN=torihiki-1 kbb -M:build
```

(Taking the set from a node, as above, is only as good as that node: check it
against what the operators publish before shipping the page.)

The build **fails** — and says every reason — on a URL that is not a plain
base URL, plain `http://` to a non-local host (`TK_ALLOW_HTTP=1`), a key that
is not Ed25519 SPKI, a duplicate witness or key, a set without its epoch or
chain, or keys a devnet derives from the chain id, which anybody can sign
with (`TK_ALLOW_DEVNET=1`). Without `TK_SET` the page still works and says
its balance is NOT a proof.

`?nodes=a,b` on the page URL still overrides the node list; it cannot
override the set or the chain.

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
