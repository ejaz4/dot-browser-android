# Dot Browser for Android

The same private browser you all know and love, rebuilt for Android in the Dot design language. Built on [GeckoView](https://mozilla.github.io/geckoview), Firefox's engine.

<p>
  <img src="docs/screenshots/welcome.png" width="200" alt="Welcome" />
  <img src="docs/screenshots/new-tab.png" width="200" alt="New tab page" />
  <img src="docs/screenshots/menu.png" width="200" alt="The Dot menu" />
  <img src="docs/screenshots/tabs.png" width="200" alt="Tabs" />
</p>
<p>
  <img src="docs/screenshots/link-peek.png" width="200" alt="Link peek" />
  <img src="docs/screenshots/pull-to-tabs.png" width="200" alt="Pulling the page into the tab grid" />
  <img src="docs/screenshots/private.png" width="200" alt="Private tab" />
  <img src="docs/screenshots/new-tab-dark.png" width="200" alt="New tab page, dark" />
</p>

## What's inside

**A normal browser, done properly.** Tabs (restored after restarts, drag to reorder, swipe to close, undo), private tabs, history and bookmarks, find in page, desktop site, downloads, screenshots, view source, a link peek with live previews, pull to refresh, page permissions and prompts, and an address bar that searches, suggests and switches tabs.

**Private by default.** Strict Enhanced Tracking Protection, HTTPS-Only mode, Global Privacy Control, tracking parameters stripped from links, and cross-site cookies isolated per site. The purple shield in the address bar counts trackers stopped on the page; tap it for the breakdown. The new tab page keeps the lifetime total.

**Firefox extensions.** Install from addons.mozilla.org ("Add to Firefox" just works) or from a file. Manage them under menu → Extensions; their toolbar buttons and popups live at the top of the menu.

**Dot Assistant, entirely on your phone.** An on-device language model ([llama.cpp](https://github.com/ggml-org/llama.cpp)) that nothing you ask ever leaves:
- **Chat tabs**: turn any tab into a chat and back. Ask anything, or have Dot do things on the web for you: it searches, opens, reads, clicks, types and scrolls, showing each step and a live view of the page it's driving. Models with vision can look at the screen itself.
- **Shake to summarise**: shake your phone on a page; light wraps the screen's edges while a summary streams in.
- **Pick your model**: ten options from 229 MB (instant) to 3 GB (smartest), including Qwen3.5, Gemma 4, LFM2.5, Phi-4 mini and SmolLM3, with a recommendation for your phone. Downloads are verified by checksum.

**The Dot design language.** The toolbar, the menu (with its navigation row and inset separators) and the tab cards are translated from desktop Dot. Icons come from the Dot icon set, with new ones drawn in the same style in [`design/icons`](design/icons). Light, dark and a deep-violet private palette; toolbar at the bottom or, like desktop, at the top.

**Something a little different.**
- Pages load with a halo: the address field's outline fills with the Dot ring gradient, then blooms.
- Everything comes from somewhere. The page unwinds into its card in the tab grid and expands back out of it; new tabs grow from the button you pressed; swiped-away pages slide the way you swiped; closed tabs shrink into the tab counter; links opened in the background fly into it.
- Pull the address bar into the page (down from the top, up from the bottom) and the page tilts back in 3D on its way to the tab grid.
- A haptic vocabulary: ticks, taps, confirmations, a thud for long-presses, a double-tap for closing, a spin for the ring.
- A living ring and a barely-there aurora on the new tab page. Long-press the ring.

## Building

Requires JDK 17+, the Android SDK (API 37.2 platform), NDK 30.0.16248370 and CMake 3.31.6. llama.cpp is a submodule:

```bash
git clone --recurse-submodules https://github.com/ejaz4/dot-browser-android
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Unit tests: `./gradlew testDebugUnitTest`.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/co/dothq/browser/tabs` | `TabManager` owns every `GeckoSession`; app-scoped, so tabs survive the window |
| `app/src/main/java/co/dothq/browser/ui` | The Dot UI kit, toolbar, menu, sheets, tab grid, new tab page, transitions, signature visuals |
| `app/src/main/java/co/dothq/browser/gecko` | Prompts and permissions |
| `app/src/main/java/co/dothq/browser/ai` | Dot Assistant: model catalog and downloads, the agent behind Chat tabs, the page bridge |
| `app/src/main/cpp` | The llama.cpp JNI bridge (`dotllm.cpp`) |
| `app/src/main/assets/dot-agent` | Built-in extension that lets the assistant read and act on pages |
| `app/src/main/java/co/dothq/browser/core` | Preferences, URLs and search engines, history/bookmarks, favicons, downloads, error pages |
| `design/icons` | Source SVGs for every icon |

<a href="https://play.google.com/store/apps/details?id=co.dothq.browser" target="_blank">
  <img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="72"/>
</a>

## ❗ Issues / Support

You can create issues [here](https://github.com/dothq/browser/issues/new) or get support from our discord server, which you can join by clicking the banner below.

<a href="https://invite.gg/dot">
  <img src="https://discordapp.com/api/guilds/525056817399726102/widget.png?style=banner2" />
</a>

## ❤️ Acknowledgements

-   Dot Browser wouldn't be possible without the amazing [Firefox open-source project](https://hg.mozilla.org/mozilla-central/), developed by [Mozilla](https://mozilla.org).

## 🤝 Contributors

-   [ejaz4](https://github.com/ejaz4) - **_Main Developer_**
-   [EnderDev](https://github.com/EnderDev) - **_Main Developer_**

## 💸 Donations

We accept donations on [Liberapay](https://liberapay.com/dothq) or [Patreon](https://patreon.com/dothq) to keep our servers running.

## 📜 License

**Dot Browser for Android** is licensed under the [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0) license.
