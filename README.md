<div align="center">

# 🌸 Project Lily

**A native, offline SQL playground for Android.**
Write real SQL, run it against a real SQLite database, see real results — no server, no network, no accounts.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-23-C97B9C?style=flat-square)
![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Offline](https://img.shields.io/badge/network-none-8FA98F?style=flat-square)
![Build](https://github.com/MrDhar/Lily/actions/workflows/android-build.yml/badge.svg)

</div>

---

## ✨ What it does

Project Lily is a lightweight SQL learning tool. It ships with a real sample database, and you write and run actual SQLite queries against it directly on your device — the same engine Android itself uses under the hood.

| | |
|---|---|
| 🖊️ **Write** | A monospace editor with live SQL syntax highlighting |
| ▶️ **Run** | Select a query to run just that one — or run everything at once |
| 📊 **See** | Real result tables, or real SQLite error messages when something's wrong |
| 📥 **Import** | Bring your own `.sql`, `.db`, `.sqlite`, or `.sqlite3` file to practice on |
| 🗂️ **Inspect** | A live schema view of every table and column in your current database |
| 🔌 **Offline** | The app requests zero permissions — no network access at all |

## 🧪 Importing your own practice files

- **`.db` / `.sqlite` / `.sqlite3`** — swaps in as your active database. Query it immediately.
- **`.sql`** — executed directly against your current database (so `CREATE TABLE` / `INSERT` statements actually build real tables and data), and loaded into the editor so you can see, tweak, and re-run it. If a statement fails, nothing is silently dropped — the script stays put with the error shown, so you can fix it and hit **Run** yourself.

Hit **Reset** any time to wipe back to the bundled sample database.

## 📱 Interface

Project Lily adapts to the device it's on:

- **Phone** — a single-column layout: toolbar up top, editor in the middle, results below.
- **Tablet** *(≥600dp width)* — a two-pane layout with a persistent sidebar showing your live schema, and a larger editor + results area alongside it.

The whole UI leans minimalist: a warm off-white background, soft lily-pink and sage accents, and thin hairline dividers instead of heavy borders.

## 🗄️ Bundled dataset

Ships with a **Parks & Recreation**-themed sample database out of the box, so there's something to query the moment you open the app — no setup required.

## 🛠️ Building it yourself

**In Android Studio:** open the project folder, then `Build → Build APK(s)`.

**From the command line:**
```bash
./gradlew assembleDebug
```

**Via GitHub Actions:** this repo includes a workflow at [`.github/workflows/android-build.yml`](.github/workflows/android-build.yml) that builds a debug APK on every push to `main` and uploads it as a downloadable artifact — no local Android SDK needed.

## 🧩 Tech

- **Language:** Kotlin, no external dependencies
- **Database:** Android's built-in `SQLiteDatabase` — the same engine, not a wrapper
- **UI:** Plain Android Views (no Compose, no AndroidX) — kept intentionally small and dependency-free
- **Min SDK:** 23 · **Target SDK:** 35

## 📄 License

Personal / educational project. No license file included — treat as all-rights-reserved unless the owner says otherwise.

---

<div align="center">
<sub>Branding: <b>Project Lily</b> 🌸 — a small pixel-art pink lily throughout the app icon and header.</sub>
</div>
