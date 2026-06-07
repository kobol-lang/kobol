<p align="center">
  <img src="assets/logo.svg" alt="" width="120">
</p>
<p align="center">
  <img src="assets/wordmark.svg" alt="Kobol" width="240">
</p>

# A Modern COBOL-Inspired Language for the JVM

> Business-logic clarity, on the JVM you already run — no mainframe required.

**Kobol** is a JVM-hosted programming language that preserves what makes COBOL enduringly
valuable — English-like readability, first-class decimal arithmetic, data-first structure, and
suitability for long-lived business programs — while bringing that same clarity to ordinary JVM
infrastructure. It runs anywhere Java runs, so no specialized mainframe environment is required.

> **Kobol (with a K) is inspired by COBOL — it is not COBOL.** It is a new, independent language
> with its own syntax, compiler, and runtime. It is not a COBOL dialect, compiler, or runtime, is
> not source-compatible with COBOL, and is not affiliated with or endorsed by any COBOL vendor or
> standards body. Existing COBOL programs don't run as-is — they need to be refitted and rewritten.
> But because **Kobol** keeps COBOL's familiar English-like, readable style, moving a codebase from
> COBOL to **Kobol** is far gentler than rewriting it in a terse, symbol-heavy language.

---

## Why **Kobol**?

COBOL processes an estimated $3 trillion in daily financial transactions. Its longevity is no
accident — it encodes decades of hard-won business-logic wisdom, and the engineers who maintain it
carry deep, genuinely valuable expertise. At the same time, teams starting *new* systems today face
a few practical realities:

- **New adoption is thin.** COBOL sits at rank 25 on TIOBE (~0.65%), and in the 2024 Stack Overflow
  Developer Survey 26.2% of those already using it planned to continue. Greenfield projects rarely
  begin in it.
- **Knowledge transfer is a growing concern.** Much of the deepest COBOL expertise rests with
  veteran engineers, many now approaching retirement, while comparatively few newcomers are learning
  the language — making succession planning genuinely hard.
- **The core language predates several now-standard needs.** A concurrency model, security
  primitives, and built-in testing aren't part of the base language — they're layered on by the
  surrounding platform. And while `COPY` copybooks and `CALL` provide code reuse, COBOL has no
  modern, versioned module system.

**Kobol** asks: what would that language look like if designed today, for the JVM, with 60 years of
programming language research — and Java 21's virtual threads, sealed classes, and pattern
matching — behind it?

---

## Core Design Principles

| Principle | Description |
|-----------|-------------|
| **Readability over brevity** | Programs should read like business specifications |
| **Data-first** | Data structures are declared separately from logic |
| **Exact arithmetic** | Decimal math is the default; no silent float rounding |
| **Batch-native** | Record-by-record file processing is a first-class pattern |
| **JVM citizen** | Full Java interoperability; runs anywhere Java runs |
| **No magic defaults** | Every behavior is explicit and predictable |
| **Secure by design** | Validation, sensitive-data types, and parameterised SQL are first-class |
| **Concurrency-ready** | `CONCURRENT` blocks backed by JVM virtual threads |
| **Built-in testing** | `TEST` blocks as first-class constructs — no external framework needed |

---

## Quick Syntax Preview

```kobol
PROGRAM InvoiceProcessor
  VERSION "1.0"

IMPORT java.time.LocalDate          -- call any JVM class directly

RECORD Invoice:
  invoice-id   : INTEGER
  customer     : TEXT(100)
  amount       : MONEY(12.2)
  paid         : BOOLEAN

DATA:
  current-invoice  : Invoice
  total-invoiced   : MONEY(14.2) = 0
  invoice-count    : INTEGER     = 0
  run-date         : DATE

DEFINE TAX-RATE : DECIMAL = 8.5

PROCEDURE Main:
  CALL LocalDate.now GIVING run-date        -- Java interop: java.time.LocalDate
  DISPLAY "=== Invoice Processor ({run-date}) ==="
  DO ProcessInvoices
  DISPLAY "Processed {invoice-count} invoices — total: ${total-invoiced}"
END-PROCEDURE

PROCEDURE ProcessInvoices:
  FOR EACH inv IN InvoiceFile:
    MOVE inv TO current-invoice
    IF NOT current-invoice.paid:
      DO ApplyTax
      ADD 1 TO invoice-count
    END-IF
  END-FOR
END-PROCEDURE

PROCEDURE ApplyTax:
  LET tax = current-invoice.amount * TAX-RATE / 100
  ADD tax TO current-invoice.amount
  ADD current-invoice.amount TO total-invoiced
END-PROCEDURE
```

**Script mode** — no boilerplate needed for small programs:

```kobol
-- hello.kbl
LET name = "World"
DISPLAY "Hello, {name}!"
```

---

## Installation

**From source** (works today — builds the native-feeling `kobol` CLI and adds it to your PATH):

```bash
./gradlew :compiler:installLocal
# installs to ~/.kobol/bin and patches your shell rc; open a new terminal
kobol --version
```

**From a published release** (native binary, no JVM required to run):

```bash
# macOS / Linux — Homebrew
brew tap kobol-lang/tap
brew install kobol

# Windows — winget (coming soon)
winget install kobol-lang.kobol
```

> **Note:** Homebrew is the first supported channel. The winget package is **coming soon**;
> until then, install on Windows via [from source](#installation) above.

---

## Usage

```bash
# Project mode (recommended — uses kobol.toml)
kobol new my-project          # scaffold (templates: batch | api | lib)
kobol build                   # compile all sources
kobol run                     # build + run the main program
kobol test                    # build + run tests in src/test/
kobol check                   # type-check only, no code gen

# Single-file mode
kobol hello.kbl               # compile a single source file
kobol hello.kbl --watch       # recompile on save
kobol hello.kbl --check       # type-check only

# Interactive
kobol                         # launch the REPL
kobol --repl                  # launch the REPL explicitly
```

**Script mode** is supported: a small `.kbl` file with no `PROGRAM` header is wrapped in an implicit
program automatically (see the Quick Syntax Preview above) — no boilerplate needed.

---

## Documentation

See the [**Kobol** Language Specification](docs/LANGUAGE_SPEC.md) for the full language reference.

## Licensing

The **Kobol** software (compiler, runtime, standard library, Gradle plugin, and
VS Code extension) is made available under your choice of two licenses:

- [Apache License 2.0](LICENSE)
- [Eclipse Public License 2.0](LICENSE-EPL-2.0.txt)

Both texts are included in this repository; choose the one that best fits your use.
Derivative works must retain the [NOTICE](NOTICE) file (Apache 2.0 §4).

The **documentation** under [`docs/`](docs/) — including the
[Language Specification](docs/LANGUAGE_SPEC.md) — is licensed separately under the
[Creative Commons Attribution 4.0 International License (CC-BY-4.0)](docs/LICENSE):
reuse and adaptation are permitted **with attribution** to The Kobol Project.
