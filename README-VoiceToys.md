# VoiceToys Phone

A customized fork of **Fossify Phone** focused on a simpler, more visual, and more reliable
calling experience. This document lists everything that was added or changed compared to the
stock Fossify/AOSP-style dialer.

> Built from the `foss` flavor (`org.fossify.phone`). Build with:
> ```bash
> export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
> export JAVA_HOME=$(/usr/libexec/java_home -v 17)
> ./gradlew assembleFossDebug
> ```

---

## 1. Contact lists (filter chips)

The Contacts screen has a horizontal **chip bar** at the bottom for filtering by list.

- **Favorites chip** plus one chip per custom list, and a trailing **"+"** chip.
- Tap a chip to filter; tap the active chip again to show **all contacts**.
- **Create** a new list from the "+" chip.
- **Long-press a list chip** for options: *Edit contacts*, *Rename*, *Set icon*,
  *Remove icon*, *Delete*, *Move left/right*.
- **Edit contacts** turns the list into a checkable picker over all contacts, so you can
  add/remove members in place.

### Custom list images
- Assign a **custom image** to any list (e.g. a company or band logo) from the gallery.
- The chip then shows **only the image** (icon-only), sized large with tight padding.
- Images are remembered across restarts (persistable URI permission).

### Favorites customization
- **Rename** the Favorites chip to any text or **emoji** (e.g. ⭐ or ♥). Empty restores the default.
- **Set / remove** a custom image for Favorites, just like any list.
- **Hide** the Favorites chip; bring it back later via **long-press on the "+" chip → Show favorites**.

---

## 2. Visual contact grids

- **Grid view** and **Combined view** render contacts as **rounded-square avatar tiles**
  with up to two initials (instead of a single-letter circle).
- Contacts with a photo show the photo; otherwise a colored tile with initials.
- **Long names wrap to two balanced lines** inside a tile (e.g. `NEMANJA → NEMA / NJA`,
  `Obezbeđenje → Obezbe / đenje`) so text stays large and readable.
- The combined tile board is visually **separated from the list** (top margin + shadow),
  so list letters no longer clip against its top edge.

---

## 3. Swipe navigation

- **Left/right swipe on the Contacts screen** moves between lists
  (All contacts → Favorites → each list, wrapping around).
- The pager's own paging swipe is **disabled** — **Dialpad** and **History** are reached
  **only via the bottom tab bar**, preventing accidental tab changes.

---

## 4. Reliable background operation

- **"Keep running in background"** option (Settings) starts a lightweight **foreground service**
  with a silent, minimized notification.
- Automatically **restarts on boot** so calls and notifications keep working reliably.
- Prompts to **ignore battery optimizations** when enabled.

---

## 5. Accounts & duplicates

- **Unified Accounts manager** (Settings → *Contact sources*):
  - Enable/disable each contact source.
  - **Rank** sources with up/down controls.
  - **Add account** shortcut.
  - **Hide duplicates** toggle.
- **Hide duplicate contacts** across accounts: when the same person exists in several accounts,
  only the copy from the **highest-ranked source** is shown (matched by phone number).
- The same **Hide duplicates** switch is also available directly in Settings.

---

## 6. Sorting & fast scrolling

- **Sort by most-frequently-called** in addition to alphabetical order.
- In frequency mode, the side wedge doubles as a **proportional frequency scrollbar**
  (drag to jump from most- to least-contacted).
- **Wider touch area** for the A–Z / frequency fast-scroller.

---

## 7. Streamlined menu & Settings

- The **three-dot overflow is replaced by a gear icon** that opens **Settings**.
- **Rank accounts** and **About** were removed from the toolbar; both now live in **Settings**
  (*Contact sources* = accounts manager, plus *About*).
- **Clear call history** moved to a **toolbar trash icon** shown on the History tab.

---

## Attribution

Based on [Fossify Phone](https://github.com/FossifyOrg/Phone) (GPL-3.0). All upstream privacy,
open-source, and performance guarantees still apply. See `LICENSE` and `CHANGELOG.md`.
