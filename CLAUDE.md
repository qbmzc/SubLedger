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

There are no tests in the project yet (no `src/test` or `src/androidTest`, no test dependencies).

Without an Android SDK, you can still type-check all sources against Robolectric's framework jar (`org.robolectric:android-all:14-robolectric-10818077` from Maven Central, ~138 MB) with a stub `R` class. The stub must declare every resource the code references: `drawable.ic_notification`, `drawable.ic_ledger`, `layout.widget`, and the `id.widget_*` ids from `res/layout/widget.xml`.

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
- **Keep bookkeeping logic Android-free.** `Subscription`, `Wallet`, `Payment`, `TopUp`, `Attachment`, `Ledger`, `Rates`, `Stats` and `BackupZip` use only `java.*`, so they can be compiled and tested with a plain `javac --release 8` harness.
- **Colours come from `LedgerActivity.Palette`**, never literals in screens. The palette is chosen by role (`hero` is the strong surface behind white text, `heroText` is secondary text on it, `green` is the accent for text and marks, `lime` is a soft accent background). Dark mode uses `Palette.DARK` plus `res/values-night/`. The widget uses `@color/widget_*` resources, which have night variants.

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
- **`Wallet`**: a prepaid balance, which may go negative. It can hold an optional `unitCost` in `costCurrency`. `topUp` updates the unit cost as a weighted average of the remaining balance and the new top-up.
- **`Payment`**: one recorded charge. It stores the bill (`amount`/`currency`) and the real `cost`/`costCurrency`, which is **fixed at record time** (converted to the base currency then, if a rate exists), so later rate changes never rewrite history. `Payment.idFor(subscriptionId, renewalDate)` is a name-based UUID, so re-recording or merging backups cannot duplicate a renewal.
- **`TopUp`**: one wallet top-up (amount in the wallet currency, plus an optional amount actually paid).
- **`Attachment`**: metadata for one JPEG or PDF file, owned by a subscription, a top-up or a payment (`ownerId`). `FILE_PATTERN` is the only file name accepted from backups.
- **`Ledger`**: a snapshot of subscriptions, wallets, payments, top-ups and attachments. The constructor drops top-ups of missing wallets and attachments of missing owners, so deleting any record also deletes its attachments. Inside `Ledger`, `with(...)` copies the snapshot while keeping the top-ups and attachments. Every bookkeeping rule is a method that returns a new `Ledger`:
  - `recordDue(rates, today)`: records each AUTO renewal in `(recordedThrough, today]`, deducts the paying wallet, ends trials, and sets `recordedThrough = today`. A subscription with no `recordedThrough` (migrated data) starts tracking without backfilling. `upsert` makes new subscriptions, and MANUAL→AUTO switches, start from yesterday.
  - `recordManualRenewal`: payment, wallet deduction, `anchor` moved one cycle forward, trial cleared.
  - `addPayment` (a backfill; never touches wallets), `removePayment(id, refund)`, `upsert`, `removeSubscription` (history is kept), `upsertWallet`, `removeWallet` (unlinks subscriptions), `merge` (by id; the backup wins).
  - `realCost(entry)`: the explicit cost, else the paying wallet's unit cost, else the bill. `payingWallet` only returns a wallet whose currency matches the bill.
  - `coverage(wallet, today)`: simulates future charges against the balance, which drives the "余额不足" (insufficient balance) warnings.
  - `topUp(record)`: updates the balance and the weighted unit cost. `removeTopUp`: subtracts the amount and leaves the unit cost unchanged. A new wallet's starting balance is recorded as its first top-up.
  - `addAttachments` (at most 20 per owner), `removeAttachment`, `keepAttachments(predicate)` (used on import to drop records whose file is missing).
- **`Rates`**: the base currency plus `fetched` and `manual` rates, both expressed as base units per 1 foreign unit. Manual rates win. `withBase` re-expresses the existing rates in the new base where it can.
- **`Stats`**: built from `(Ledger, Rates, today)`. The estimates (monthly, yearly, the 12-month projection, and the item/category/payment/cycle shares) use `realCost` and `myShare` over non-ended subscriptions. Projections skip charges already recorded today. Spent totals (this year, last 12 months) come from `Payment.mine`. Costs without a rate end up in `missing` and `unconvertedPayments`.

### Persistence

- **`LedgerStore`**: the whole `Ledger` is stored as one JSON string in SharedPreferences `ledger`/`subscriptions`, written with `commit()`. A `revision` counter changes on every save, so screens can tell when to re-render. `encode`/`decode` are also the user-facing backup format, currently **version 3** (`LedgerStore.VERSION`):
  - version 1: subscriptions only;
  - version 2: added the optional cost;
  - version 3: added the category, trial, split, wallet link, prices, `recordedThrough`, and the `wallets` and `payments` arrays;
  - version 4: added the `topUps` and `attachments` arrays.
  Older versions still decode. Ids must be unique across all three arrays. **Changing the JSON shape changes the backup format.** Bump `VERSION` and keep decoding the older versions.
- **`BackupZip`**: the backup file is a ZIP containing `ledger.json` plus `attachments/<file>`. `read` extracts into a staging directory, accepts only `ledger.json` and names matching `Attachment.FILE_PATTERN` (this prevents zip slip), and enforces per-entry and total size caps. Import auto-detects ZIP vs plain JSON by the `PK\3\4` header, so old `.json` backups still import.
- **`AttachmentStore`** (Android): copies picked files into `cache/incoming` on a background thread. Images are re-encoded to JPEG with the longest edge at most 2400 px, EXIF rotation applied; PDFs are checked for the `%PDF` header. The files move into `files/attachments` on the UI thread right before `commit`. `commit` calls `prune`, which deletes unreferenced files, so a file must never sit in the attachments folder without a record about to be saved. `AttachmentStore` also provides thumbnails through an `LruCache`.
- **`RateStore`**: SharedPreferences `rates`/`state`. All mutations go through `update(UnaryOperator)`, which is synchronized against background downloads. A download result is discarded if the base currency changed while it ran. Rates are **not** part of the backup.

### Android layer

- **`LedgerActivity`**: abstract base for every screen.
  - `Palette`.
  - The view helpers: `column`, `row`, `panel`, `label`, `pill`, `action`, `input`, `suggestInput`, `check`, `options`, `addField`, `dp`, `money`, `percent`, `parseAmount`, `chooseDate`.
  - `screen(title, headerAction)`, the standard sub-page with a back button that keeps its scroll position.
  - `commit(Supplier<Ledger>)`, the write path. It also prunes attachment files.
  - `AttachmentStrip`, a thumbnail row with an add button for one owner. Strips rebuild themselves in `onResume`. Picking files goes through `pickAttachments`, with `PICK_ATTACHMENTS` handled in the base `onActivityResult`, so subclasses must call `super`. When the files are saved, the base class calls `onLedgerChanged()`, which subclasses override to re-render.
  - The background rate refresh.
- **`MainActivity`**: the list, summary card, wallet strip, subscription editor and details dialogs (the details dialog includes the attachment strip), settings menu, and backup import/export (SAF, background `Thread`, ZIP export, ZIP or JSON import through a staging directory in the cache).
- **`AttachmentActivity`**: viewer for one attachment. Images are scaled to the screen width; PDFs show their first 5 pages through `PdfRenderer`. Deleting is done here. `onResume` re-renders when the date or the store revision has changed, or when charges were just recorded. Otherwise it only refreshes the summary card.
- **`StatsActivity`**: totals, the custom-drawn `MonthBars` view (tap a bar to see that month's charges), the share rows, and rate management.
- **`WalletsActivity`**: wallet list with coverage and top-up records, plus create, edit, top-up and delete. Each top-up has a details dialog with its invoices.
- **`HistoryActivity`**: payments grouped by month, optionally filtered by the `SUBSCRIPTION` or `WALLET` extra. Tapping a payment opens its details with receipts. It supports manual backfill and deletion with an optional wallet refund.
- **`RenewalReminder`**: a `BroadcastReceiver` and the static `refresh(context)`, which is idempotent. It records due charges (`LedgerStore.recordDue`), updates the widget, and posts due reminders at 09:00 local time. Trials get "试用即将结束" (trial ending soon) wording, and an insufficient wallet is mentioned in the text. A receipt `"<renewalDate>/<reminderDays>"` per subscription id in SharedPreferences `reminders` prevents duplicate posts, and a single inexact `setAndAllowWhileIdle` alarm is scheduled. It runs on boot, package replace, time/timezone change, its own alarm, `MainActivity.onResume`, widget updates, and after every `commit`.
- **`LedgerWidget`**: an `AppWidgetProvider` (`res/xml/ledger_widget.xml`, 6-hour update period). `onUpdate` calls `RenewalReminder.refresh`, which calls `LedgerWidget.update`.

### Write-path invariant

Every mutation goes through `commit(() -> ledgerStore().load().<operation>(...))`. It saves the result and runs `RenewalReminder.refresh`, which also updates the widget. Callers then re-render. When an edit changes the anchor, cycle, mode, trial or `reminderDays`, when a manual renewal is recorded, or when a subscription is deleted, call `RenewalReminder.clearReceipt(id)` **before** `commit`. Otherwise the stale receipt suppresses the new reminder or leaves an orphaned notification.

### "Claude 模板"

The editor has a template mode (`editor(null, true)`) that prefills "Claude Pro", the category AI, and a hard-coded known renewal date (`templateDate`, currently 2026-12-12; it falls back to today once that date has passed). The README states that the user must confirm the amount and date before saving.

## Distribution note (from README)

The prebuilt test APK is signed with a test key. Users who switch to a self-built APK must export a backup (ZIP with attachments), uninstall, install the new build, then import. Keep the same signing key for future updates.
