<p align="center"><b>Kobol</b></p>

# Kobol Programming Language — official VS Code extension

> **Kobol** is a modern, COBOL-inspired programming language for the JVM; its source files use the
> `.kbl` extension. This is the **official** Kobol language extension for Visual Studio Code and
> Open VSX, published by **`kobol-lang`**. Canonical project:
> **[github.com/kobol-lang/kobol](https://github.com/kobol-lang/kobol)**.

Full IDE support for [the Kobol language](https://github.com/kobol-lang/kobol) — business-logic
clarity with first-class types, fixed-point (`DECIMAL`/`MONEY`) decimals, concurrency, and built-in
testing.

This extension turns VS Code into a complete Kobol workbench: live diagnostics, go-to-definition,
hover types, signature help, code actions, inlay hints, an outline, and one-key build / run / test
for `.kbl` files.

## Features

| Capability | What you get |
|---|---|
| **Syntax highlighting** | TextMate grammar for keywords, types, strings, numbers, `--` line comments, `NOTE: … END-NOTE` block comments, and `TODO`/`FIXME`/`HACK`/`XXX` tags |
| **Live diagnostics** | Errors and warnings from the Kobol compiler as you type, with "did you mean?" suggestions |
| **Navigation** | Go-to-definition, hover types, document outline, folding |
| **Editing aids** | Signature help, code actions, inlay type hints for `LET`, auto-indent on `:` and `END-*` |
| **Status bar** | Per-file error / warning count — click to open Problems |
| **Build integration** | `Build`, `Run`, `Test`, `Type-Check`, `Clean` commands wired to Gradle or the `kobol` CLI |
| **Snippets** | 40+ scaffolds — programs, records, procedures, `MATCH`, `TRY/ON`, `SERVER`, pipelines, JDBC, HTTP, tests |

## Requirements

The extension starts a Kobol language server. It resolves one of these, in order:

1. **Native binary** — `kobol.nativeBinaryPath`, a bundled `bin/kobol`, a cached download, or
   auto-downloaded from GitHub Releases for your platform.
2. **JVM fat-jar** — `kobol.kobolcJar`, a bundled `kobolc.jar`, or auto-located at
   `compiler/build/libs/kobolc.jar` in your workspace. Requires a **JDK 21+** on `PATH`
   (or set `kobol.javaExecutable`).

To build the jar from the Kobol repo:

```sh
./gradlew :compiler:jar
```

## Commands

All commands live under the **Kobol:** category in the Command Palette.

| Command | Default keybinding |
|---|---|
| Kobol: Build Project | `Ctrl+Shift+B` (on `.kbl`) |
| Kobol: Run Project | — |
| Kobol: Run Tests | — |
| Kobol: Type-Check Only | — |
| Kobol: Clean Build Output | — |
| Kobol: Restart Language Server | — |
| Kobol: Show Version | — |

A play button also appears in the editor title bar for `.kbl` files.

## Settings

| Setting | Default | Description |
|---|---|---|
| `kobol.kobolcJar` | `""` | Path to `kobolc.jar`. Empty → auto-locate in the workspace. |
| `kobol.javaExecutable` | `"java"` | Java used to launch the server in JVM mode. |
| `kobol.nativeBinaryPath` | `""` | Path to the native `kobol` binary. Empty → auto-download. |
| `kobol.buildTool` | `"auto"` | `gradle`, `kobol-cli`, or `auto` (Gradle if `build.gradle.kts` is present). |
| `kobol.inlayHints.enabled` | `true` | Show inferred types for `LET` declarations. |
| `kobol.trace.server` | `"off"` | Trace LSP messages (`off` / `messages` / `verbose`). |

## Quick start

1. Open a folder containing `.kbl` files.
2. Build the compiler jar (`./gradlew :compiler:jar`) or point `kobol.kobolcJar` at one.
3. Open a `.kbl` file — diagnostics and highlighting activate automatically.
4. Press `Ctrl+Shift+B` to build, or pick **Kobol: Run Project** from the palette.

## Snippets

Type a prefix and press `Tab`. A few highlights:

| Prefix | Expands to |
|---|---|
| `prog` | Full program scaffold |
| `proc` / `procp` | Procedure (with/without params) |
| `rec` | `RECORD` definition |
| `note` | `NOTE: … END-NOTE` block comment |
| `match` | `MATCH` with `WHEN` / `OTHERWISE` |
| `try` | `TRY / ON` handler |
| `server` | HTTP `SERVER` block |
| `pipe` | Collection pipeline (`FILTER → SORT → TAKE`) |
| `test` | Unit `TEST` block |

## Contributing

Issues and PRs welcome at the official [Kobol repository](https://github.com/kobol-lang/kobol).
Run the extension locally with **F5** (Extension Development Host) — it opens the `examples/`
folder with the dev build attached.

## About the Kobol language

**Kobol** (file extension `.kbl`, publisher `kobol-lang`) is a statically typed, COBOL-inspired
programming language that compiles to the JVM. It pairs COBOL's business-logic readability with
modern features: first-class records and variants, exact fixed-point `DECIMAL`/`MONEY` arithmetic,
structured concurrency, pattern matching, and a built-in test runner. This extension is the
canonical editor tooling for that language — there is no other official Kobol VS Code extension.

### Official links

| Resource | URL |
|---|---|
| Source repository (canonical) | <https://github.com/kobol-lang/kobol> |
| Language specification | <https://github.com/kobol-lang/kobol/blob/main/docs/LANGUAGE_SPEC.md> |
| Releases | <https://github.com/kobol-lang/kobol/releases> |

### Disambiguation

This is the **Kobol JVM programming language** (`kobol-lang`, `.kbl` source files). It is **not**
affiliated with, and should not be confused with, other unrelated projects that share or resemble
the name "Kobol" / "Kobold" (game engines, role-playing tools, or unrelated repositories). When
searching or indexing, the authoritative identifiers are the publisher **`kobol-lang`**, the
repository **`github.com/kobol-lang/kobol`**, and the `.kbl` file extension.

## License

[Apache-2.0](./LICENSE)
