# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

订阅账本 (SubscriptionLedger): a small personal Android app for tracking subscriptions, prepaid balances and payment history (local data only; the network is used solely to download exchange rates). Package `com.subledger.mobile`, minSdk 26, targetSdk 34, compileSdk 35. User-facing strings (UI text, toasts, exception messages) are written in Simplified Chinese; keep new strings consistent with that.

## Build

Requires the Android 35 SDK and JDK 17+.

```bash
./gradlew :app:assembleDebug     # output: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lint              # Android lint (default config, no custom rules)
```

CI: `.github/workflows/build.yml` runs `assembleDebug` on pushes to `main` (APK uploaded as an artifact) and publishes a GitHub Release for `v*` tags. When `SIGNING_KEYSTORE` is set (CI decodes it from repository secrets), `app/build.gradle` signs the debug build with that fixed key so updates install over each other; `versionCode` comes from `GITHUB_RUN_NUMBER`. Never commit keystores (`*.jks` is gitignored).

There are no tests in the project yet (no `src/test` or `src/androidTest`, no test dependencies).

Without an Android SDK, you can still type-check all sources against Robolectric's framework jar (`org.robolectric:android-all:14-robolectric-10818077` from Maven Central, ~138 MB) with a stub `R` class. The stub must declare every resource the code references: `drawable.ic_notification`, `layout.widget`, `style.SheetOverlay`, `style.BrandColors`, and the `id.widget_*` ids from `res/layout/widget.xml` (the widget's root uses `android.R.id.background`). There is no device to run on in that setup, so UI changes are only checked by CI's `assembleDebug` and by hand.

```bash
javac --release 8 -nowarn -encoding UTF-8 -cp android-all.jar -d out R.java app/src/main/java/com/subledger/mobile/*.java
```

The same jar's real `org.json` classes can be put on the runtime classpath to exercise `LedgerStore.encode`/`decode` from a plain `main` harness.

## Hard constraints

- **No dependencies.** `app/build.gradle` declares no libraries: no AndroidX, no AppCompat, no Material Components, no Kotlin. Use only framework APIs (`android.app.Activity`, `AlertDialog`, `Notification.Builder`, `AppWidgetProvider`, `org.json`, `java.time`).
- **Java 8 source level** (`sourceCompatibility 1_8`). Lambdas are fine. Newer language features (`var`, records, switch expressions, text blocks) are not. `java.time` works without desugaring because minSdk is 26.
- **Network use is limited to exchange rates.** `INTERNET` exists only for `RateStore.download()` (GET `open.er-api.com/v6/latest/<BASE>`). Never send subscription data off the device. The first download happens only when the user taps a button. After that, rates older than 12 hours refresh automatically when the app or the statistics screen opens. The UI must keep the "Rates By Exchange Rate API" attribution. `allowBackup="false"`.
- **No XML layouts for app screens.** All activity UI is built in code. The only layout XML is `res/layout/widget.xml`, because `RemoteViews` requires one.
- **Attachments stay on the device** in `files/attachments/<id>.jpg|pdf`. They are never uploaded anywhere; "upload" in the UI means picking a file into the app.
- **Keep bookkeeping logic Android-free.** `Subscription`, `Wallet`, `Payment`, `TopUp`, `Attachment`, `Icons`, `MonthlyReport`, `Ledger`, `Rates`, `Stats` and `BackupZip` use only `java.*`, so they can be compiled and tested with a plain `javac --release 8` harness.
- **Colours come from `LedgerActivity.Palette`**, never literals in screens. The palette is chosen by role (`hero` is the strong surface behind `onHero` text, `heroText` is secondary text on it, `green` is the accent for text and marks, `lime` is a soft accent background, `raised` is a surface lifted above `line`, `scrim` dims behind sheets, `ripple` is the pressed overlay). On Android 12+ the palette is built from the wallpaper's tonal palettes (`system_accent*` / `system_neutral*`, Material You) unless the user turns it off in settings (SharedPreferences `appearance`/`dynamic`); then, and before Android 12, it is the app's green `Palette.LIGHT` / `Palette.DARK`. The theme matches through `res/values-v31/` and `values-night-v31/` (dynamic accent and window background) and the `BrandColors` overlay, applied in `LedgerActivity.onCreate` when wallpaper colours are off. The widget uses `@color/widget_*` resources with night and `-v31` (wallpaper) variants.
- **Shapes are continuous-curvature.** Rounded surfaces use `Squircle` (via `shape()` / `ripple()`), not `GradientDrawable` corners; only full circles (e.g. switch knobs) use ovals.
- **Adaptive layout by window width** (`widthDp()`): compact < 600 dp (phones), `medium()` 600–839, `expanded()` ≥ 840 (desktop windows). Screens cap their content width and lay cards out with `grid(...)`/`columns(...)`; never assume a phone-width screen.

## Architecture

All source files are in `app/src/main/java/com/subledger/mobile/`.

### Model (pure Java, immutable)

- **`Subscription`**: created and copied through `Subscription.Builder` (`toBuilder()`). The constructor is the single validation point and throws `IllegalArgumentException`, which callers catch and show to the user. It covers the id/wallet UUIDs, the text field lengths, the year range 1900–9998, `reminderDays` −1..90, `splitWays` 1..99, at most 50 price points, and currency-scale checks through `validMoney`.
  - Date math: `anchor` is the date the user entered. For `AUTO`, renewals are `anchor + n·cycle` (`occurrence`, `indexOnOrAfter`). For `MANUAL`/`ENDED`, `anchor` itself is the next date.
  - Optional fields:
    - `category`.
    - `trial`: the anchor is the trial end and first charge.
    - `splitWays`: `myShare()` divides by it.
    - `walletId`.
    - `cost`/`costCurrency`: an explicit real cost per period.
    - `prices`: old prices, appended by the editor when the amount or currency changes.
    - `recordedThrough`: see `Ledger.recordDue`.
    - `lastDue(today)`: the start of the running billing period (latest renewal ≤ today; for MANUAL, one cycle before the anchor), null for trials, ended subscriptions or before the first renewal.
    - `iconText`, `iconColor` (RGB), `iconImage` (id of `<id>.jpg` in the attachments folder): icon overrides, each null when automatic. `iconFile()` gives the picture's file name.
- **`Wallet`**: a prepaid balance, which may go negative. It can hold an optional `unitCost` in `costCurrency`. `topUp` updates the unit cost as a weighted average of the remaining balance and the new top-up.
- **`Payment`**: one recorded charge. It stores the bill (`amount`/`currency`) and the real `cost`/`costCurrency`, which is **fixed at record time** (converted to the base currency then, if a rate exists), so later rate changes never rewrite history. `Payment.idFor(subscriptionId, renewalDate)` is a name-based UUID, so re-recording or merging backups cannot duplicate a renewal.
- **`TopUp`**: one wallet top-up (amount in the wallet currency, plus an optional amount actually paid).
- **`MonthlyReport`**: a month's statement (not a backup): CSV with BOM (payments, top-ups, total of the user's share; cells are quoted and formula-defused) plus the month's payment and top-up files renamed `发票/<date> <title> <currency> <amount>.pdf|jpg` (unique, sanitized). `months(ledger)` lists months with records.
- **`Icons`**: text badges. The user's text/colour win, else a `PRESETS` entry matched by keyword in the lower-case title (abbreviation + brand colour only; never bundle or download logos), else the title's first code point on an `AUTO` colour chosen by a stable hash. `Badge.textColor()` picks black or white by WCAG contrast; every `AUTO` colour must keep white text at ≥ 4.5:1.
- **`Attachment`**: metadata for one JPEG or PDF file, owned by a subscription, a top-up or a payment (`ownerId`). `FILE_PATTERN` is the only file name accepted from backups.
- **`Ledger`**: a snapshot of subscriptions, wallets, payments, top-ups and attachments. The constructor drops top-ups of missing wallets and attachments of missing owners, so deleting any record also deletes its attachments. Inside `Ledger`, `with(...)` copies the snapshot while keeping the top-ups and attachments. Every bookkeeping rule is a method that returns a new `Ledger`:
  - `recordDue(rates, today)`: records each AUTO renewal in `(recordedThrough, today]`, deducts the paying wallet, ends trials, and sets `recordedThrough = today`. A subscription with no `recordedThrough` (migrated data) starts tracking without backfilling. `upsert` makes new subscriptions, and MANUAL→AUTO switches, start from yesterday.
  - `recordManualRenewal`: payment, wallet deduction, `anchor` moved one cycle forward, trial cleared.
  - `addPayment` (a backfill; never touches wallets), `removePayment(id, refund)`, `upsert`, `removeSubscription` (history is kept), `upsertWallet`, `removeWallet` (unlinks subscriptions), `merge` (by id; the backup wins).
  - `realCost(entry)`: the explicit cost, else the paying wallet's unit cost, else the bill. `payingWallet` only returns a wallet whose currency matches the bill.
  - `coverage(wallet, today)`: simulates future charges against the balance, which drives the "余额不足" (insufficient balance) warnings.
  - `topUp(record)`: updates the balance and the weighted unit cost. `removeTopUp`: subtracts the amount and leaves the unit cost unchanged. A new wallet's starting balance is recorded as its first top-up.
  - Invoices belong to billing periods: a period is a `Payment`, so its files are attachments owned by the payment id. Files owned by the subscription itself are "订阅资料" (not tied to a period). `paymentsOf(subscriptionId)` (newest first), `payment(id)`, `moveAttachment(id, owner)` (respects the per-owner cap), `regroupAttachments()` (one-time migration for data before v6: subscription-owned files move to the payment of the month they were added).
  - `addAttachments` (at most 20 per owner), `removeAttachment`, `keepFiles(predicate)` (used on import to drop attachments and clear icon pictures whose file is missing).
  - `fileNames()`: every stored file the ledger refers to (attachments and icon pictures). Pruning and the ZIP export use it.
- **`Rates`**: the base currency plus `fetched` and `manual` rates, both expressed as base units per 1 foreign unit. Manual rates win. `withBase` re-expresses the existing rates in the new base where it can.
- **`Stats`**: built from `(Ledger, Rates, today)`. The estimates (monthly, yearly, the 12-month projection, and the item/category/payment/cycle shares) use `realCost` and `myShare` over non-ended subscriptions. Projections skip charges already recorded today. Spent totals (this year, last 12 months) come from `Payment.mine`. Costs without a rate end up in `missing` and `unconvertedPayments`.

### Persistence

- **`LedgerStore`**: the whole `Ledger` is stored as one JSON string in SharedPreferences `ledger`/`subscriptions`, written with `commit()`. A `revision` counter changes on every save, so screens can tell when to re-render. `encode`/`decode` are also the user-facing backup format, currently **version 6** (`LedgerStore.VERSION`):
  - version 1: subscriptions only;
  - version 2: added the optional cost;
  - version 3: added the category, trial, split, wallet link, prices, `recordedThrough`, and the `wallets` and `payments` arrays;
  - version 4: added the `topUps` and `attachments` arrays;
  - version 5: added the subscription icon fields (`iconText`, `iconColor` as `#RRGGBB`, `iconImage`);
  - version 6: no shape change; marks invoices as kept per billing period. Decoding version < 6 runs `Ledger.regroupAttachments()` (the stored data is upgraded on the next save).
  Older versions still decode. Ids must be unique across all arrays. **Changing the JSON shape changes the backup format.** Bump `VERSION` and keep decoding the older versions.
- **`BackupZip`**: the backup file is a ZIP containing `ledger.json` plus `attachments/<file>`. `read` extracts into a staging directory, accepts only `ledger.json` and names matching `Attachment.FILE_PATTERN` (this prevents zip slip), and enforces per-entry and total size caps. Import auto-detects ZIP vs plain JSON by the `PK\3\4` header, so old `.json` backups still import.
- **`AttachmentStore`** (Android): copies picked files into `cache/incoming` on a background thread. Images are re-encoded to JPEG with the longest edge at most 2400 px, EXIF rotation applied; PDFs are checked for the `%PDF` header. The files move into `files/attachments` on the UI thread right before `commit`. `commit` calls `prune`, which deletes unreferenced files, so a file must never sit in the attachments folder without a record about to be saved. `AttachmentStore` also provides thumbnails through an `LruCache`. Icon pictures (`copyIcon`: centre-cropped 256 px JPEG) follow the same rule: picked into `cache/incoming`, moved in by `moveIcon` just before the editor saves, discarded when the editor is cancelled.
- **`RateStore`**: SharedPreferences `rates`/`state`. All mutations go through `update(UnaryOperator)`, which is synchronized against background downloads. A download result is discarded if the base currency changed while it ran. Rates are **not** part of the backup.

### Android layer

- **`LedgerActivity`**: abstract base for every screen.
  - `Palette` (see the colour constraint above).
  - Edge to edge: `edgeToEdge(window, surface)` lets activity and sheet windows draw behind the system bars (legacy `SYSTEM_UI_FLAG_LAYOUT_*` flags, which work on every supported version); `bars(insets)` returns the bar insets plus the keyboard height, and views pad themselves. `insetLeft/Top/Right/Bottom` hold the latest values.
  - `Page`: the screen scaffold. Content scrolls under a pinned bar that turns opaque (with a hairline) once something scrolls under it; with a large title, the bar's compact title fades in when the large one scrolls away (iOS). Optional bar actions (`action`), an extended FAB (`fab`) that shrinks to its icon while scrolling down, and on expanded windows a side pane (`side`) that scrolls on its own. `screen(title, headerAction[, maxWidthDp])` is the standard sub-page (back button, large title) and keeps its scroll position across re-renders.
  - The view helpers: `column`, `row`, `panel`, `group`/`addRow`/`factRow` (grouped-table cards), `label`, `heading`, `amount` (tabular figures), `pill`, `action`, `tappable`, `input`, `suggestInput`, `check` (a `Switch` row), `segmented`, `options`, `pickerField`, `addField`, `hint`, `section`, `grid`/`columns`, `dp`, `money`, `percent`, `parseAmount`, `chooseDate`.
  - Dialogs: `sheet(title)` creates a `Sheet`; `menu(title, items, handlers, destructive)` is an action sheet; `alert()` is an `AlertDialog.Builder` whose dialogs get the palette surface and continuous corners. Use alerts only for short confirmations; forms, details and lists go in sheets.
  - `commit(Supplier<Ledger>)`, the write path. It also prunes attachment files.
  - `iconView(entry, title, size)`: the subscription icon (`IconDrawable`: picture or text badge on a continuous-corner tile, with a `line`-coloured ring). Shown on list cards, the details sheet, statistics item rows and payment rows (a deleted subscription falls back to a badge from the payment title).
  - Attachment actions shared by all screens: tap a tile to open `AttachmentActivity`, long-press for view / move to another period (`moveTargets`, `moveAttachment`) / delete (`deleteAttachment`); `refreshStrips()` rebuilds every visible strip.
  - Monthly export: `chooseMonthToExport()` and `exportMonth(month)` (SAF save dialog, `EXPORT_MONTH` handled in the base `onActivityResult`, `MonthlyReport.write` on a background thread).
  - `AttachmentStrip`, a thumbnail row with an add button for one owner. Strips rebuild themselves in `onResume`. Picking files goes through `pickAttachments`, with `PICK_ATTACHMENTS` handled in the base `onActivityResult`, so subclasses must call `super`. When the files are saved, the base class calls `onLedgerChanged()`, which subclasses override to re-render.
  - The background rate refresh.
- **`Sheet`**: modal bottom sheet in its own full-screen transparent `Dialog` window (`R.style.SheetOverlay`). It rests at detents given as fractions of the room below the status bar (`detents(0.6f, 1f)`; short content gets fewer, close detents merge), follows the finger on its header, hands the gesture to its `ScrollView` and back (intercept plus nested scrolling), rubber-bands past the top, settles with a `Motion.Spring` that keeps the fling velocity, and closes when pulled down, tapped outside or backed out of. `unsaved(check)` makes those closes confirm first (`cancel()` does the same for a 取消 button); `dismiss()` closes without asking. The keyboard opens it fully. Header: `leading` text button, title (`titleOnScroll()` hides it until the body's own large title scrolls away), `trailing` button whose action returns true to close, or a close button. Capped at 640 dp and centred on wide windows.
- **`Squircle`**: drawable with continuous-curvature corners (Figma corner smoothing, 0.6 like iOS), per-corner radii, optional stroke and padding. `addTo(path, …)` is reused by `IconDrawable`. Its outline is the path on Android 13+ (which can clip) and a round rect before.
- **`Motion`**: `Spring` (damped harmonic, analytic, driven by `ValueAnimator` so the system animation scale applies), `pressable(view, scale)` (press-to-shrink `StateListAnimator`), `tick(view)` haptics, and the M3 `EMPHASIZED` easing.
- **`Segmented`**: segmented control whose thumb springs between segments; used for the list filter and the editor's cycle and mode.
- **`Glyph`**: a few line icons (back, close, chevrons, plus) drawn in code so they take palette colours.
- **`MainActivity`**: the list, summary (hero card with figures, wallet card), subscription editor sheet (the icon sits beside the name and opens the icon sheet: picture, text, colour swatches, hex colour; optional fields fold under "更多" unless already filled; closing with changes asks first) and details sheet (next renewal, facts, price changes, the latest billing periods, each with its invoice strip, an offer to backfill the running period when it has no payment, and a separate "订阅资料" strip), long-press quick actions on cards, settings menu (including the wallpaper-colour switch, which recreates the activity), and backup import/export (SAF, background `Thread`, ZIP export, ZIP or JSON import through a staging directory in the cache). Compact: one column with a large title; medium: hero and wallets side by side, cards in a grid; expanded: two panes (overview with shortcuts on the start side, the list beside it). `onResume` re-renders when the date or the store revision has changed, or when charges were just recorded; otherwise it only refreshes the summary card.
- **`AttachmentActivity`**: viewer for one attachment. Images are scaled to the content width (at most 960 dp); PDFs show their first 5 pages through `PdfRenderer`. Shows which record owns the file, with "move to another period" and delete buttons.
- **`StatsActivity`**: totals, the custom-drawn `MonthBars` view (grows in once per visit; tap a bar to open that month's charges in a sheet), the share rows, and rate management (rate and base-currency sheets). Medium and wider windows show two columns.
- **`WalletsActivity`**: wallet cards (in a grid on wider windows) with coverage and top-up records, plus create, edit, top-up and delete sheets. Each top-up has a details sheet with its invoices.
- **`HistoryActivity`**: payments grouped by month in grouped-table cards (each month header has an export button), optionally filtered by the `SUBSCRIPTION` or `WALLET` extra. Tapping a payment opens its details sheet with receipts. It supports manual backfill and deletion with an optional wallet refund.
- **`RenewalReminder`**: a `BroadcastReceiver` and the static `refresh(context)`, which is idempotent. It records due charges (`LedgerStore.recordDue`), updates the widget, and posts due reminders at 09:00 local time. Trials get "试用即将结束" (trial ending soon) wording, and an insufficient wallet is mentioned in the text. A receipt `"<renewalDate>/<reminderDays>"` per subscription id in SharedPreferences `reminders` prevents duplicate posts, and a single inexact `setAndAllowWhileIdle` alarm is scheduled. It runs on boot, package replace, time/timezone change, its own alarm, `MainActivity.onResume`, widget updates, and after every `commit`.
- **`LedgerWidget`**: an `AppWidgetProvider` (`res/xml/ledger_widget.xml`, 6-hour update period). `onUpdate` calls `RenewalReminder.refresh`, which calls `LedgerWidget.update`. The root is `@android:id/background`, so Android 12+ launchers round it with their own radius and animate opening the app.
- **App icon**: adaptive (`mipmap-anydpi-v26/ic_launcher.xml`) with a monochrome layer for Android 13 themed icons.

### Write-path invariant

Every mutation goes through `commit(() -> ledgerStore().load().<operation>(...))`. It saves the result and runs `RenewalReminder.refresh`, which also updates the widget. Callers then re-render. When an edit changes the anchor, cycle, mode, trial or `reminderDays`, when a manual renewal is recorded, or when a subscription is deleted, call `RenewalReminder.clearReceipt(id)` **before** `commit`. Otherwise the stale receipt suppresses the new reminder or leaves an orphaned notification.

### "Claude 模板"

The editor has a template mode (`editor(null, true)`) that prefills "Claude Pro", the category AI, and a hard-coded known renewal date (`templateDate`, currently 2026-12-12; it falls back to today once that date has passed). The README states that the user must confirm the amount and date before saving.

## Distribution note (from README)

The prebuilt test APK is signed with a test key. Users who switch to a self-built APK must export a backup (ZIP with attachments), uninstall, install the new build, then import. Keep the same signing key for future updates.
