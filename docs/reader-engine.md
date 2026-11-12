# Reader engine

## Provenance / distribution

- Upstream: https://github.com/johnfactotum/foliate-js
- Pinned commit: `78914aef4466eb960965702401634c2cb348e9b1`.
- `app/src/main/assets/reader/foliate-js/` contains byte-for-byte upstream
  `view.js`, `epubcfi.js`, `progress.js`, `overlayer.js`, `text-walker.js`,
  `paginator.js`, `epub.js`, `fixed-layout.js`, and `vendor/zip.js`.
- No upstream demo UI, PDF/MOBI/FB2/CBZ adapters, fflate, search, or TTS modules.
  `view.js` is unmodified and still contains optional dynamic imports for these
  unsupported features; the bridge never invokes them. Local imports must be EPUB.
- `index.html` and `reader.js` are the application's shell/bridge, not upstream UI.
- Licenses: `licenses/FOLIATE-MIT.txt` (John Factotum, 2022),
  `licenses/ZIPJS-BSD.txt` (zip.js 2.8.22, Gildas Lormeau, 2023), and
  `licenses/SJCL-BSD.txt` (embedded SJCL; BSD branch selected, not GPL).
  zip.js version comes from upstream's pinned `package-lock.json`; its BSD text
  was checked against `@zip.js/zip.js-2.8.22.tgz` from registry.npmjs.org.
  The SJCL notice is the complete BSD branch of upstream SJCL `LICENSE.txt`.
  Preserve all three notices in distributions; the minified zip bundle has no
  complete license header. Packaging/native license UI remains the host's job.

## Native contract

Load only `https://appassets.androidplatform.net/assets/reader/index.html`.
Use WebViewAssetLoader for the trusted assets and native interception for:

- `GET /session.json`: `{book:{id,title,author,local}, chapters:[{id,title,volume}],
  initialCfi, initialChapterId, startChapterId, settings}`.
- `GET /book.epub`: the selected local EPUB, when `book.local=true`.
- `GET /chapter/<encodedBookId>/<encodedChapterId>.xhtml`: sanitized, UTF-8 XHTML
  for one online chapter, with a real `<head>` and `<body>`. Decode each path
  segment exactly once; map IDs to a native book/chapter, never an arbitrary URL.

Settings default to `{fontSize:20,lineHeight:1.8,theme:'paper',scroll:false,
serif:true,keepScreenOn:true}`. Themes are paper `#F6F1E7`, white `#FFFFFF`, and
night `#171717`. Reflowable content is single-column, with 24 CSS px margins,
20 CSS px text, 1.8 line spacing, and 2em paragraph indentation by default.
The host handles `keepScreenOn`; JS does not use Wake Lock. Fixed-layout EPUB
keeps its publisher layout: font/flow overrides are deliberately skipped.
Settings and theme changes do not rebuild the book or emit ready/loaded.
`volume` is available to the native UI; engine/native-ready TOC is flat.

Register a source-limited WebViewCompat.addWebMessageListener named `Native`.
Only allow the appassets origin and accept messages from the main frame.
The shell calls `window.Native.postMessage(JSON.stringify(message))`:

- `{type:'ready',toc:[{label,href}],fixed:boolean}` after successful first layout.
- `{type:'loaded'}` after a genuine successful document load, before its relocate.
- `{type:'relocate',cfi,fraction,chapter:string,chapterId:string}`; no DOM objects.
- `{type:'tap'}` for a short, unmoved center click without a selection or link.
- `{type:'error',message:string}` for fetch/markup/engine failures.

The first successful open and a successful retry send
`ready → loaded → relocate`. In particular, a book opened without a subsequent
page turn still records an initial valid reading position. After an error,
loaded clears the native error before a valid relocate is sent. Faulted readers
suppress further relocation messages until retry. No error document is rendered.
Settings alone never send loaded and therefore cannot dismiss a native error.
Without a native bridge, messages are appended as objects to `window.__messages`
for test assertions. That fallback is not a second native transport.

The only application command object exposed is `window.Reader`:

```js
window.Reader.commands({type:'next'})
window.Reader.commands({type:'prev'})
window.Reader.commands({type:'goto',href:'...chapter URL / EPUB TOC href / CFI...'})
window.Reader.commands({type:'fraction',fraction:0.5})
window.Reader.commands({type:'settings',settings:{theme:'night',fontSize:22}})
window.Reader.commands({type:'retry'})
```

Commands are serialized; outstanding repeated next/prev commands are coalesced.
Internal links use the same queue. Foliate retains touch/swipe and selection
handling. Single clicks are delayed briefly to leave double-click selection
alone. Long presses, drag gestures, selections, and interactive/link targets do
not turn pages or toggle the toolbar. No gesture calls preventDefault here.
The shell has no visible toolbar, footer, status text, or error UI. Native owns
all user feedback. The initial toolbar state is the native default (visible).

## Loading, locations, and retry

Online: construct every section/TOC entry synchronously from session metadata,
without fetching chapter bodies. Each section has a stable URL ID and `size:1`.
Only `section.load()` fetches its chapter. Concurrent loads of that section share
one promise. Successful, validated XHTML becomes a Blob URL. `unload()` revokes
it; rebuilding/destroying the book revokes all remaining URLs. Kotlin owns the
persistent cache, request deduplication, source fetching, authentication, and 503
responses. No whole-novel EPUB generation, speculative prefetch, or full-book
search occurs in this shell. Relative links resolve against the original chapter
URL, not the Blob URL. Images/styles/fonts must already use absolute controlled
appassets URLs; relative resource paths cannot resolve against Blob URLs.

Online progress is **equal chapter weighting**, not word counts or fixed page
numbers. Fractions are clamped for transport; CFI is the preferred precise
location. Foliate's byte-based location/time estimates are not exposed.
The fake CFI chapter component is section-index-based. Persist chapterId as well
as CFI. On initial open: an explicit startChapterId wins; otherwise accept an
initial CFI only if its decoded section index matches initialChapterId; otherwise
open that chapter's beginning. If the old ID no longer exists, open the first
chapter. A valid-section but stale DOM CFI falls back to its chapter beginning.
Appending chapters preserves existing indices; inserting/reordering chapters can
invalidate fake CFIs. Within-chapter content/DOM changes can also invalidate them.

Local: fetch `/book.epub`, verify a ZIP signature, wrap its Blob in a named EPUB
File, then call real `makeBook(File)` and `view.open(book)`. CFI takes priority;
invalid initial CFI falls back to the first linear section. The online chapter
list is not used to render local EPUB. Local chapterId is the section ID/path.

The bridge uses real renderer.goTo(view.resolveNavigation(target)) deliberately:
upstream view.goTo catches exceptions. Section loads are wrapped to catch errors
also when Foliate's own touch handler initiates a load. Fetch failures throw and
emit error; they never return an error/empty chapter to the paginator. Navigation
has a deadline. Retry rebuilds the view/book and replays the last requested target
(including a chapter reached by a failed gesture). This clears upstream's possibly
locked/corrupted paginator state without modifying its source. Session/EPUB
fetches that fail before opening can also be retried. Partial local font/resource
errors and the upstream renderer's teardown behavior still need device testing.

## WebView and security boundary

Require a modern, updated Chromium WebView. Feature detection covers native ES
module import failures, Object.groupBy/Map.groupBy, deflate-raw DecompressionStream,
custom elements, ResizeObserver, WeakRef, Intl.Locale, Web Crypto, container queries,
and CSS nesting. Unsupported WebViews report `请更新 WebView` through Native.
The native host must also handle failure to load the shell/module itself (JS
cannot report anything if reader.js never executes). Older/disabled WebViews
are unsupported; Android API level alone is not a compatibility check.
No bundler, CDN, worker, or WASM is required for the included ZIP-core path.

- Shell CSP defaults to none. Scripts are self-only, with script elements further
  restricted to the trusted `/assets/reader/` prefix; no inline/eval/blob scripts.
  Styles may be self/blob/inline; connections self/blob; frames self/blob;
  images/fonts self/blob/data; objects, base URLs, and forms are blocked.
- Both EPUB and online XHTML Blob documents receive an additional head CSP
  forbidding scripts, frames, objects, base URLs, and forms. The wrapper also
  strips script/frame/object/embed/base elements, event attributes, srcdoc,
  JavaScript/VBScript URLs, and refresh metadata. EPUB transformTarget rejects
  manifest resources with `isScript`. These are defense in depth, **not a general
  untrusted-document sanitizer or a browser sandbox security guarantee**.
- Online native responses must still carry `script-src 'none'` CSP headers and
  undergo robust native sanitization. Response headers do not transfer when
  content is reconstructed as a Blob; shell inheritance plus the inserted CSP
  are therefore needed too. Native must canonicalize resources to its local
  allowlist and remove unexpected active/external content before caching.
- Upstream creates same-origin iframes with `allow-same-origin allow-scripts` for
  its compatibility needs. Those upstream files are unchanged; the shell never
  adds iframe privileges or enables book scripting. Do not treat that sandbox
  as isolation from the trusted shell or native bridge.
- Always cancel external-link. The native host must deny external navigation,
  new windows, downloads, unexpected schemes, permissions, and subresource URLs.
  Return a blocked response for interception misses, not null/network fallback.
  Restrict `/session.json`, `/book.epub`, chapter and resource routes to this
  active session; never expose arbitrary files/paths or an open HTTP proxy.
- Disable file/content/universal-file access, mixed content, and release remote
  debugging. Never use addJavascriptInterface. Check message origin **and** main
  frame. INTERNET permission may be used by Kotlin; the WebView need not reach
  real source sites. WebViewAssetLoader alone does not disable network access.
- Limit archive entry count, uncompressed size, chapter size, and network duration
  natively. DRM is not supported; supported font obfuscation is not DRM support.

## Verification / remaining device checks

Run syntax checks with `node --input-type=module --check < reader.js`.
Vendor files should hash identically to the pinned upstream checkout.
Node-only bridge tests cannot validate Chromium pagination or actual CSP.
Android device/emulator coverage must include initial record without a page turn;
503 → retry → loaded → relocate; slow/failing cross-chapter swipes; directory
reordering and startChapterId; EPUB reflow/fixed layouts/obfuscated fonts/images;
selection and long press/double click; rotation/font/theme/scroll changes; memory
and Blob revocation; lifecycle recreation; and malicious EPUB/URL/CSP/bridge
isolation tests. Keep the engine pinned: upstream has no stable API promise.
