<p align="center">
  <img src="./icon.png" width="96" height="96" alt="Kobol logo" />
</p>

# Kobol Language Support

Full IDE support for [Kobol](https://github.com/kobol-lang/kobol) — a modern, COBOL-inspired
language for the JVM. Business-logic clarity with first-class types, fixed-point decimals,
concurrency, and testing.

This extension turns VS Code into a complete Kobol workbench: live diagnostics, go-to-definition,
hover types, signature help, code actions, inlay hints, an outline, and one-key build / run / test.

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

Issues and PRs welcome at the [Kobol repository](https://github.com/kobol-lang/kobol).
Run the extension locally with **F5** (Extension Development Host) — it opens the `examples/`
folder with the dev build attached.

## License

[Apache-2.0](./LICENSE)
