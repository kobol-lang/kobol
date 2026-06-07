<!--
  Kobol Language Specification
  SPDX-License-Identifier: CC-BY-4.0
  Copyright 2026 The Kobol Project.

  Canonical source of truth: https://github.com/kobol-lang/kobol (docs/LANGUAGE_SPEC.md)
  Published reference:        https://kobol-lang.org/spec
  Spec version:               Draft v0.1

  This specification text is licensed under the Creative Commons Attribution 4.0
  International License (CC-BY-4.0): https://creativecommons.org/licenses/by/4.0/
  See docs/LICENSE. Reuse and adaptation are permitted with attribution to The
  Kobol Project; attribution must not imply endorsement or affiliation.

  The Kobol compiler and tooling (all source outside docs/) are licensed
  separately under Apache-2.0 OR EPL-2.0 — see /LICENSE and /LICENSE-EPL-2.0.txt.
-->
<p align="center">
  <img src="../assets/logo.svg" alt="" width="120">
</p>
<p align="center">
  <img src="../assets/wordmark.svg" alt="Kobol" width="240">
</p>

# Language Specification (Draft v0.1)

> **About the examples.** Many ` ```kobol ` blocks below are *fragments* shown for
> illustration and are not complete, compilable programs. Blocks that are full programs
> (they begin with a `PROGRAM` header) are compiled in CI by `SpecExamplesCompileTest`,
> so they cannot rot. To enroll a script-mode example (no `PROGRAM` header) in that gate,
> make its first line `-- @compile`.

## 1. Lexical Structure

### 1.1 Character Set
**Kobol** source files are UTF-8 encoded. Source is free-format; no column significance.

### 1.2 Case Sensitivity
Keywords are **case-insensitive**. Identifiers are **case-insensitive** (normalized to
uppercase internally). `balance`, `BALANCE`, and `Balance` all refer to the same identifier.

### 1.3 Identifiers
```
identifier  ::= letter (letter | digit | '-')*
letter      ::= [A-Za-z_]
digit       ::= [0-9]
```

Identifiers may contain hyphens (`-`) as word separators, matching COBOL convention.
A hyphen may not appear at the start or end of an identifier.

Valid:   `CUSTOMER-NAME`, `invoice-count`, `tax-rate-2024`
Invalid: `-name`, `name-`, `3rd-field`

### 1.4 Keywords (Reserved)

> This list is generated from the lexer's keyword table (`Lexer.KEYWORDS`) and is
> verified in CI by `KeywordSpecSyncTest` — it cannot silently drift from the compiler.
> Every word below is reserved and may not be used as an identifier (`PROGRAM` name,
> variable, procedure, etc.). Multi-word terminators (`END-IF`, …) are single tokens.

```
ADD             ALL             AND             AS              ASSERT
ASYNC           AT              AUTHOR          AWAIT           BODY
BOOLEAN         CACHE           CALL            CLOSE           COMBINE
COMPUTE         CONCURRENT      CONDITION       CONFIG          COUNT
DATA            DATABASE        DATE            DATETIME        DEBUG
DECIMAL         DEFINE          DELETE          DEPRECATED      DISPLAY
DIVIDE          DO              EACH            ELSE            END-CONCURRENT
END-CONFIG      END-FOR         END-IF          END-MATCH       END-MODULE
END-PERFORM     END-PRECISION   END-PROCEDURE   END-PROGRAM     END-RECORD
END-SERVER      END-TEST        END-TRY         END-VALIDATE    END-VARIANT
END-WHILE       END-WITH        ENDPOINT        ENSURE          ERROR
EXCEPT          EXPIRES         EXPORT          EXTEND          FALSE
FILES           FILTER          FIND            FOR             FROM
FUTURE          GET             GIVING          HEADERS         IF
IMPORT          IN              INFO            INPUT           INTEGER
INTO            IS              KEY             LABEL           LET
LIST            LOG             MAP             MATCH           MILLISECONDS
MINUTES         MOCK            MODULE          MONEY           MOVE
MULTIPLY        MUST            NEW             NOSQL           NOT
OF              ON              OPEN            OR              OTHERWISE
OUTPUT          PARALLEL        PARAMS          PARSE           PERFORM
PORT            PRECISION       PROCEDURE       PROGRAM         PUT
RAISE           READ            RECORD          REPEAT          RESPOND
RETURN          RETURNING       ROUND           RUN             SAVE
SECONDS         SENSITIVE       SERVER          SET             SLEEP
SMALLINT        SORT            STATUS          STOP            SUBTRACT
SUM             TAKE            TEST            TEXT            TIME
TIMEOUT         TIMES           TO              TRACE           TRANSFORM
TRUE            TRY             USING           UUID            VALIDATE
VARIANT         VERSION         WAIT            WARN            WHEN
WHERE           WHILE           WITH            WRITE
```

### 1.5 Literals
```
integer-literal   ::= digit+
decimal-literal   ::= digit+ '.' digit+
string-literal    ::= '"' (any char except '"' | '""')* '"'
boolean-literal   ::= TRUE | FALSE
```

### 1.6 Comments
```
-- single line comment
-- TODO: tagged comments (TODO/FIXME/HACK/XXX) surface as compiler diagnostics

NOTE:
  multi-line comment — free text until END-NOTE
END-NOTE
```

### 1.7 Operators
```
Arithmetic:  + - * / **  (** = exponentiation)
Comparison:  = <> < > <= >=
Logical:     AND OR NOT
Assignment:  (via MOVE, COMPUTE, ADD, SUBTRACT, MULTIPLY, DIVIDE)
```

---

## 2. Program Structure

A **Kobol** source file contains exactly one program unit. The top-level structure:

```
program             ::= program-header
                        import-section?
                        record-section*
                        constant-section?
                        data-section?
                        condition-section*
                        procedure-section+
                        END-PROGRAM?

program-header      ::= PROGRAM identifier
                        (VERSION string-literal)?
                        (AUTHOR string-literal)?
```

### 2.1 Program Header

```kobol
PROGRAM CustomerBilling
  VERSION "1.0"
  AUTHOR  "Billing Team"
```

`VERSION` and `AUTHOR` are informational metadata; they do not affect compilation.

---

## 3. Import Section

```
import-section  ::= (IMPORT qualified-name (AS identifier)?)+
qualified-name  ::= identifier ('.' identifier)*
```

```kobol
IMPORT java.time.LocalDate
IMPORT java.math.BigDecimal      AS BigDec
IMPORT com.example.CustomerRepo  AS CustRepo
```

Imported classes are available by their short name (or alias) in procedure bodies.

---

## 4. Record Section (Data Structures)

Records are the primary user-defined data type. They map to JVM classes.

```
record-decl  ::= RECORD identifier ':'
                   field-decl+
                 END-RECORD?

field-decl   ::= identifier ':' type-spec (condition-decl)*

type-spec    ::= INTEGER
               | SMALLINT
               | DECIMAL '(' integer-literal ',' integer-literal ')'
               | MONEY   '(' integer-literal '.' integer-literal ')'
               | TEXT
               | TEXT '(' integer-literal ')'
               | BOOLEAN
               | DATE
               | TIME
               | DATETIME
               | LIST OF type-spec
               | MAP OF type-spec TO type-spec
               | identifier                   -- named record type (nested)
```

### 4.1 Simple Record

```kobol
RECORD Customer:
  customer-id   : INTEGER
  customer-name : TEXT(50)
  balance       : MONEY(12.2)
  active        : BOOLEAN
  joined-date   : DATE
END-RECORD
```

### 4.2 Nested Record

```kobol
RECORD Address:
  street  : TEXT(80)
  city    : TEXT(40)
  state   : TEXT(2)
  zip     : TEXT(10)

RECORD Employee:
  emp-id      : INTEGER
  name        : TEXT(60)
  home-addr   : Address      -- nested record type
  department  : TEXT(30)
  salary      : MONEY(10.2)
```

**Value semantics.** A record is a value, not a shared handle. Storing a record into
any holder — another record variable (`MOVE rec TO rec`), a `LIST` element
(`ADD rec TO list`), a `MAP` value (`PUT rec TO map`), a record-typed field of a
record literal (`Box { it: rec }`), or a record-typed field of a `VARIANT` case
(`Full(rec)`) — stores an independent **copy** of that record, so later mutation of the
source record's own fields never leaks into the stored value (and vice-versa).

The copy is **deep**: a record-typed *field* of the copied record is itself copied
recursively, at every level. Mutating a source's nested record (e.g. `a.inner.f` after
`MOVE a TO b`) never affects the stored copy. Scalars, `TEXT`, and `DECIMAL`/`MONEY`
values are immutable, so they need no copy. A field left uninitialized (null) stays null
through the copy.

### 4.3 Condition Declarations (Named Boolean Conditions)

Inspired by COBOL's 88-level items:

```
condition-decl ::= CONDITION identifier WHEN expression
```

```kobol
RECORD Account:
  status-code   : TEXT(1)
    CONDITION Active   WHEN status-code = "A"
    CONDITION Inactive WHEN status-code = "I" OR status-code = "D"
    CONDITION Frozen   WHEN status-code = "F"
  balance       : MONEY(12.2)
    CONDITION Overdrawn WHEN balance < 0
    CONDITION AtLimit   WHEN balance >= credit-limit
  credit-limit  : MONEY(12.2)
```

Usage:
```kobol
IF account.Active:
  PERFORM ProcessTransaction
END-IF
IF account.Overdrawn:
  DISPLAY "Account " account.account-id " is overdrawn"
END-IF
```

---

## 5. Constant Section

```
constant-section  ::= DEFINE identifier ':' type-spec '=' literal
                    | DEFINE TYPE identifier IS type-spec
```

### 5.1 Value Constants

```kobol
DEFINE TAX-RATE     : DECIMAL = 8.5
DEFINE MAX-RECORDS  : INTEGER = 99999
DEFINE APP-NAME     : TEXT    = "Billing System v1.0"
DEFINE PI           : DECIMAL = 3.14159265358979
```

Constants are immutable and global to the program unit.

### 5.2 Type Aliases

A `DEFINE TYPE` declaration assigns a meaningful domain name to a type, enabling
consistent precision and preventing accidental mismatches across the program.

```kobol
DEFINE TYPE ExchangeRate     IS DECIMAL(18,8)
DEFINE TYPE CustomerCode     IS TEXT(10)
DEFINE TYPE TransactionLimit IS MONEY(12,2)
DEFINE TYPE BatchSize        IS INTEGER
```

Type aliases may be used everywhere a type specifier is accepted:

```kobol
DATA:
  eur-rate  : ExchangeRate
  gbp-rate  : ExchangeRate         -- consistent 18,8 precision enforced
  cust-id   : CustomerCode
  daily-cap : TransactionLimit

RECORD FxQuote:
  base-currency  : CustomerCode
  quote-currency : CustomerCode
  rate           : ExchangeRate
  as-of          : DATE

PROCEDURE ApplyRate USING amount : TransactionLimit, rate : ExchangeRate
                    RETURNING TransactionLimit:
  RETURN ROUND amount * rate TO 2
END-PROCEDURE
```

Type aliases are resolved at compile time — `ExchangeRate` is identical to
`DECIMAL(18,8)` in all type-checking rules. They may be exported from modules:
`EXPORT TYPE ExchangeRate`.

---

## 6. Data Section (Working Storage)

```
data-section  ::= DATA ':'
                    data-item+

data-item     ::= identifier ':' type-spec ('=' literal)?
```

```kobol
DATA:
  current-invoice    : Invoice
  total-processed    : INTEGER     = 0
  grand-total        : MONEY(14.2) = 0
  report-date        : DATE
  error-message      : TEXT
  invoice-buffer     : LIST OF Invoice
```

Variables declared in `DATA:` are program-global (working storage). They are initialized
to their defaults if no value is given:

| Type | Default Value |
|------|--------------|
| `INTEGER`, `SMALLINT` | `0` |
| `DECIMAL`, `MONEY` | `0` (see note on scale below) |
| `TEXT`, `TEXT(n)` | `""` (empty / spaces for fixed) |
| `BOOLEAN` | `FALSE` |
| `DATE`, `TIME`, `DATETIME` | **No implicit default** — must be initialized explicitly |
| `LIST OF T` | Empty list |
| `MAP OF K TO V` | Empty map |
| `UUID` | Nil UUID: `00000000-0000-0000-0000-000000000000` |
| Record type | All fields at their defaults |

> **Temporal types have no implicit default.** Earlier drafts promised "current date at
> program start" for an uninitialized `DATE`. That was COBOL-style hidden state (§1.7) and
> the value was actually `null`, NPE-ing on first use. Initialize temporal fields explicitly,
> e.g. `started : DATE = TODAY`. The compiler emits warning `W019` at the declaration site
> for any uninitialized `DATE`/`TIME`/`DATETIME`.
>
> The same `W019` fires for an uninitialized `JAVA-OBJECT` field (an imported class held in a
> DATA field, §8.11) — it also defaults to `null`. Beyond the first-use NPE, passing such a null
> into a Kotlin **non-null** parameter fails that callee's entry `null`-check immediately.
> Initialize it, e.g. `buf : SB = NEW SB`.

> **Decimal default scale.** A `DECIMAL`/`MONEY` default zero is *stored* as `0` (scale 0), the
> same value an explicit `= 0` produces — the declared scale is a *precision budget* for the
> stored value, not a storage normalization. No precision is lost: `BigDecimal` retains full
> precision and arithmetic uses max-scale semantics.
>
> **Decimal display scale.** `DISPLAY` and string interpolation render a `DECIMAL`/`MONEY` at its
> **declared scale**, zero-padding when the value carries fewer fraction digits — an uninitialized
> `DECIMAL(18,8)` (and an explicit `= 0`) DISPLAYs as `0.00000000`, and `DECIMAL(18,8) = 1.5` as
> `1.50000000`. This is presentation only; the stored value is unchanged. Padding is **one-way**:
> a value that carries *more* fraction digits than the declared scale is shown in full and is
> never rounded down, so `DISPLAY` can never hide stored precision.

---

## 7. Procedure Section

```
procedure-decl  ::= PROCEDURE identifier
                      (USING parameter-list)?
                      (RETURNING type-spec)?
                      ':'
                        statement*
                    END-PROCEDURE?

parameter-list  ::= parameter (',' parameter)*
parameter       ::= identifier ':' type-spec
```

### 7.1 Simple Procedure (No Parameters)

```kobol
PROCEDURE Main:
  DISPLAY "Starting..."
  PERFORM ProcessRecords
  STOP RUN
END-PROCEDURE
```

### 7.2 Parameterized Procedure

```kobol
PROCEDURE ApplyDiscount USING invoice : Invoice, rate : DECIMAL RETURNING Invoice:
  COMPUTE discount = invoice.amount * rate / 100
  SUBTRACT discount FROM invoice.amount
  RETURN invoice
END-PROCEDURE
```

### 7.3 Entry Point
The procedure named `Main` (case-insensitive) is the program entry point.

---

## 8. Statements

### 8.1 MOVE
Assigns a value to a variable or record field.

```
MOVE expression TO identifier ('.' identifier)*
MOVE identifier TO identifier   -- record copy (field-by-field)
```

```kobol
MOVE 0             TO invoice-count
MOVE customer-name TO current-name
MOVE source-record TO target-record   -- deep copy
```

### 8.2 COMPUTE
Evaluates an arithmetic or string expression and assigns the result.

```
COMPUTE identifier ('.' identifier)* = expression
```

```kobol
COMPUTE tax        = amount * TAX-RATE / 100
COMPUTE full-name  = COMBINE first-name " " last-name
COMPUTE days-late  = DUE-DATE - TODAY
COMPUTE net-pay    = gross-pay - deductions - (gross-pay * TAX-RATE / 100)
```

Operator precedence (high to low): `**`, `* /`, `+ -`, comparison, `NOT`, `AND`, `OR`.

### 8.3 ADD / SUBTRACT / MULTIPLY / DIVIDE
Arithmetic shorthand verbs for common operations:

```kobol
ADD tax-amount   TO invoice-total
ADD 1            TO record-count

SUBTRACT discount FROM invoice-amount

MULTIPLY quantity    BY unit-price GIVING line-total
MULTIPLY 1.08        BY base-price           -- updates base-price in place

DIVIDE total-sales BY sales-count GIVING average-sale
DIVIDE quantity    INTO total-cost           -- total-cost = total-cost / quantity
```

`GIVING` clause writes the result to a separate variable, leaving the source unchanged.

### 8.4 DISPLAY
Writes output to standard output. Multiple values are concatenated.

```
DISPLAY expression (',' expression)*
```

```kobol
DISPLAY "Total: " grand-total
DISPLAY "Customer " customer-id ": " customer-name " - Balance: " balance
DISPLAY ""    -- blank line
```

### 8.5 PERFORM
Calls a named procedure (procedure invocation).

```
PERFORM identifier (USING argument-list)?
```

```kobol
PERFORM ValidateRecord
PERFORM ApplyDiscount USING current-invoice, 10.0
```

**Capturing a return value.** `PERFORM` is a statement and does not bind a result.
To capture the value a procedure `RETURN`s, call it as an expression:

```kobol
COMPUTE tax-due = CalculateTax(invoice.amount)
COMPUTE tax-due = Utils.CalculateTax(invoice.amount)   -- module-qualified
```

A `GIVING` clause on `PERFORM` is reserved for `ASYNC` procedures, where it binds the
`FUTURE OF T` handle (see §18.4). Using `GIVING` on a synchronous `PERFORM` is an error
(`E215`); use expression-call capture instead.

This works identically for a **module-qualified** async `PERFORM`: the captured
`FUTURE OF T` is awaited the same way, with no cross-module restriction.

```kobol
PERFORM Jobs.Fetch USING 21 GIVING fut   -- fut : FUTURE OF INTEGER
AWAIT fut INTO result                    -- result = 42
```

A non-`FUTURE` `GIVING` target is an error (`E216`).

### 8.6 IF / ELSE / END-IF

```
IF condition ':'
  statement*
(ELSE IF condition ':'
  statement*)*
(ELSE ':'
  statement*)?
END-IF
```

```kobol
IF balance > 0:
  PERFORM ApplyInterest
ELSE IF balance = 0:
  DISPLAY "Zero balance - no action"
ELSE:
  DISPLAY "WARNING: Negative balance " balance
END-IF
```

The `END-IF` terminator is optional when blocks are unambiguously delimited by indentation,
but is recommended for nested or long blocks.

### 8.7 WHILE

```
WHILE condition ':'
  statement*
END-WHILE
```

```kobol
WHILE record-count < MAX-RECORDS AND NOT end-of-file:
  PERFORM ReadNextRecord
  ADD 1 TO record-count
END-WHILE
```

### 8.8 FOR EACH

```
FOR EACH identifier IN expression ':'
  statement*
END-FOR
```

```kobol
FOR EACH invoice IN invoice-list:
  IF NOT invoice.paid:
    PERFORM ProcessInvoice
  END-IF
END-FOR
```

### 8.9 REPEAT

```
REPEAT integer-expression TIMES ':'
  statement*
END-REPEAT
```

```kobol
REPEAT 3 TIMES:
  PERFORM AttemptConnection
END-REPEAT
```

### 8.10 STOP RUN
Terminates program execution with exit code 0. Equivalent to `System.exit(0)`.

```kobol
STOP RUN
STOP RUN WITH EXIT-CODE 1     -- non-zero exit
```

### 8.11 CALL (Java Interop)
Invokes a Java method directly.

```
CALL qualified-method-name (WITH argument-list)? (GIVING identifier)?
```

```kobol
CALL LocalDate.now GIVING today
CALL CustRepo.save WITH current-customer
CALL System.currentTimeMillis GIVING start-time
```

For a **static** interop `CALL`, an **instance** call on a typed receiver (`TEXT`,
`DECIMAL`/`MONEY`, `DATE`/`TIME`/`DATETIME`, `LIST`, `MAP`, `UUID` — mapped to `String`,
`BigDecimal`, the `java.time` types, `List`/`Map`/`UUID`), **and** an instance call on a
`JAVA-OBJECT` produced by `NEW` (whose concrete class is carried from the `NEW` site), the compiler
reads the owner class off the compile classpath and links to the method's real signature: an
overloaded target is resolved by ranking candidates on argument-coercion cost (e.g. an `INTEGER`
arg widens to a `long`/`double` parameter), and the method's real return type is captured into the
`GIVING` target (e.g. a `double` result lands in a `DECIMAL`, an `int` result widens into an
`INTEGER`). A **varargs** method is matched too — the trailing arguments are packed into its array
parameter (reference element types). When the class is unreadable, the overload set is genuinely
ambiguous, or a needed coercion is unsupported, the compiler falls back to Kobol-side descriptor
inference. Because Kobol `INTEGER` is a 64-bit `long`, an `INTEGER` argument passed to a Java method
that takes an `int` parameter is narrowed via a **guarded** conversion (`Math.toIntExact`): an
in-range value passes through, a value outside `int` range raises a clear `ArithmeticException` at
run time — the narrowing is never silent. A `JAVA-OBJECT` also keeps its class **across a procedure
boundary**: declare the holder — a `USING` parameter, a `RETURNING` type, or a `DATA` field — with
an imported class name and the instance `CALL` resolves against that class wherever the object
travels (see §8.11.3). The **`NEW` constructor** is resolved the same way (see §8.11.2).
Primitive-element varargs (`int…`) use Kobol-side descriptor inference.

#### 8.11.1 CALL as an expression

A `CALL` may also appear in **expression** position — directly as the right-hand side of a
`COMPUTE` or `LET`, or nested inside a larger expression — so an interop result can be used
without a separate `GIVING` variable:

```
call-expression ::= CALL qualified-method-name (WITH argument-list)?
```

```kobol
LET n = CALL Math.max WITH a, b           -- n is INTEGER (Math.max(long,long) → long)
COMPUTE tail = CALL greeting.substring WITH 1
COMPUTE doubled = (CALL Math.max WITH a, b) * 2
```

The method's **real return type** is read off the compile classpath at type-check time and becomes
the static type of the expression — so `LET` infers the variable's type from it and a `COMPUTE`
target is checked against it. This is resolved by the **same** machinery as the statement form, so
the inferred type always matches the emitted call. A `CALL` to a **void** method, an unresolvable
owner, or an overload set that is ambiguous for the given argument types is a **compile error** (it
never type-checks clean only to fail when loaded). A trailing `WITH a, b` binds greedily into the
argument list; wrap the call in parentheses to use its result inside a larger expression, as in
`(CALL Math.max WITH a, b) * 2`. The reflective `alias.STATIC_FIELD.method` form is statement-only.

#### 8.11.2 NEW (constructing a Java object)

`NEW` constructs an instance of a classpath / 3rd-party class — the constructor counterpart
to `CALL`. The owner is resolved the same way (`IMPORT` alias, stdlib path, `java.lang.*`, or
fully-qualified name). The result is a `JAVA-OBJECT`; bind it with `LET` and use it as an
instance receiver in a later `CALL`.

```
new-expression ::= NEW class-name (WITH argument-list)?
```

```kobol
IMPORT "java.lang.StringBuilder" AS SB
LET buf = NEW SB WITH "hello"      -- StringBuilder("hello")
CALL buf.append WITH " world"
LET empty = NEW SB                 -- no-argument constructor
```

Arguments are **positional**. The constructor is resolved against the **real class on the
compile classpath** — the same classpath-aware machinery a `CALL` uses — so an overloaded or
primitive-`int` constructor links correctly: a Kobol `INTEGER` (a 64-bit `long`) is narrowed
into an `int` parameter through the same guarded `Math.toIntExact` conversion as `CALL`
(narrowing is the last resort and never silent — it raises a clear `ArithmeticException` on a
value outside `int` range). When the owner class cannot be read, or two constructors tie at the
same coercion cost, `NEW` falls back to a descriptor inferred from the Kobol-side argument types
(a string literal → `String`, an `INTEGER` → `long`). Named constructor arguments are not
supported — use positional arguments.

#### 8.11.3 An imported class as a declared type

An `IMPORT` alias (or the simple imported class name) may be used as a **declared type** — for a
`DATA` field, a `USING` parameter, or a `RETURNING` type. The holder is a `JAVA-OBJECT` that
remembers its concrete class, so an instance `CALL` on it links to the real methods even after the
object is passed to another procedure or returned from one (a local `LET x = NEW …` already carries
its class within the same procedure; a declared type extends that across boundaries).

```kobol
IMPORT "java.lang.StringBuilder" AS SB

PROCEDURE AppendWorld USING b : SB:
  CALL b.append WITH "world"          -- b keeps its StringBuilder class here
END-PROCEDURE

PROCEDURE Main:
  LET sb = NEW SB
  CALL sb.append WITH "hello "
  PERFORM AppendWorld USING sb        -- same object, class preserved across the call
  CALL sb.toString GIVING result      -- "hello world"
END-PROCEDURE
```

A real `RECORD`/`VARIANT` of the same name takes precedence over an import alias. Only a single
identifier resolves this way — a dotted name (`java.time.LocalDate`) is not a type name, so import
it under an alias first. The value's JVM representation is still erased to `Object`; the class is
used for compile-time method resolution and a runtime cast, not stored in the field's descriptor.

#### 8.11.4 Kotlin nullable returns (`W237`)

Kobol has no `null`. When an interop `CALL` resolves to a **Kotlin** method whose return
type is **nullable** (`T?`), the compiler decodes the class's Kotlin `@Metadata` off the compile
classpath (a nullable `String?` and a non-null `String` erase to the *same* JVM descriptor, so the
difference is visible only in the metadata) and emits warning **`W237`** at the call site — the
result may be `null` and would surface as a `NullPointerException` only later, when used. Guard the
source or call a non-null alternative. The warning covers both call forms — the `CALL` expression
(`LET x = CALL …`) and the `CALL` statement that captures the return (`CALL … GIVING x`); a
fire-and-forget `CALL` with no `GIVING` discards the value and does not warn. A Java/JDK method
carries no Kotlin metadata, so it never trips this warning. The warning also covers top-level
functions of a Kotlin library compiled to a *multifile-class facade* (the common `XxxKt` split
across files — e.g. kotlin-stdlib `StringsKt`): the facade declares no API methods of its own and
names only its part classes, so the compiler follows those names, reads each part's metadata, and
resolves the function (and its nullability) from the part — whether the facade forwards to the part
or, as in kotlin-stdlib, inherits from it. Single-file facades and ordinary class/companion methods
are likewise covered. `suspend` functions are callable via the FUTURE bridge (§8.11.6). Parameter
nullability is not surfaced.

#### 8.11.5 Property accessors (`obj.field`)

A field read on a `JAVA-OBJECT` whose concrete class is known — a value from `NEW`, or a parameter
typed by an `IMPORT` alias — resolves to that property's getter off the compile classpath: a Kotlin
`val/var foo: T` (or a Java bean property) exposes `getFoo()`/`isFoo()`, and `obj.foo` infers the
real property type and emits the getter call. A nullable Kotlin property (`T?`) warns `W237` at the
read site, exactly as a nullable method return does (§8.11.4). The match is case-insensitive, so the
upper-cased Kobol identifier `obj.nickname` still binds the camelCase `getNickname`. A field on an
*opaque* `JAVA-OBJECT` (one whose concrete class was erased crossing a procedure boundary without an
alias type) is not resolvable this way and falls back to `TEXT`; read it with an explicit
`CALL obj.getFoo` instead. Property *writes* (`MOVE x TO obj.field`) are not yet supported.

#### 8.11.6 `suspend` functions (the FUTURE bridge)

A Kotlin `suspend fun f(args): T` does not have the JVM shape it reads as in source — the compiler
appends a hidden `kotlin.coroutines.Continuation` parameter and erases the return to `Object`, so it
is really `f(args, Continuation)Object`. The compiler decodes the `isSuspend` flag from the class's
Kotlin `@Metadata` and **bridges** the call into a `CompletableFuture`, so a `suspend` function is
consumed through the ordinary `FUTURE`/`AWAIT` machinery — no new async surface:

```kobol
IMPORT "com.acme.Fetcher" AS Fetcher
DATA:
  fut  : FUTURE OF TEXT
  name : TEXT
PROCEDURE Main:
  LET f = NEW Fetcher
  CALL f.loadName WITH user-id GIVING fut   -- suspend fun loadName(id): String
  AWAIT fut INTO name                        -- blocks until the coroutine completes
```

`CALL` supplies only the declared arguments; the `Continuation` is synthesised by the bridge. The
result, **if captured**, must go to a `FUTURE OF T` target — a non-`FUTURE` `GIVING` is **`E237`**
(it would store a `CompletableFuture` into the wrong slot and crash at run). A fire-and-forget
`suspend CALL` (no `GIVING`) launches the work and discards the future, exactly like a fire-and-forget
async `PERFORM`. A body that completes without actually suspending resolves the future immediately; a
body that suspends resolves it when the coroutine resumes, and a thrown failure completes the future
exceptionally (re-raised by `AWAIT`). **Limitations:** the bridge uses an empty coroutine context, so
there is no structured-concurrency scope — cancellation, timeouts, and dispatcher selection are not
modelled; a `suspend` function with a nullable return is not flagged `W237` (the erased `Object`
return hides the metadata key); overload-ambiguous `suspend` targets fall back to the unresolved path.

### 8.12 RAISE
Throws an exception.

```kobol
RAISE ApplicationError "Customer record not found: " customer-id
RAISE IOException "Cannot read file: " file-path
```

### 8.13 RETURN
Returns a value from a procedure that has a `RETURNING` clause.

```kobol
RETURN computed-value
```

### 8.14 SLEEP

Pauses execution for a specified duration. Useful for retry loops with exponential
backoff, polling, and rate-limited API calls.

```
sleep-stmt ::= SLEEP integer-expr (MILLISECONDS | SECONDS | MINUTES)
```

```kobol
-- Fixed delay:
SLEEP 500 MILLISECONDS
SLEEP 2 SECONDS
SLEEP 1 MINUTES

-- Retry with exponential backoff:
LET delay-ms : INTEGER = 100
REPEAT 3 TIMES:
  TRY:
    DO CallPaymentGateway USING request
  ON ApplicationError AS e:
    LOG WARN "Gateway call failed, retrying in {delay-ms}ms: {e.message}"
    SLEEP delay-ms MILLISECONDS
    COMPUTE delay-ms = delay-ms * 2
  END-TRY
END-REPEAT
```

Backed by `Thread.sleep()` on the JVM. Inside a `CONCURRENT` block, `SLEEP`
suspends only the virtual thread running that branch — other branches continue
unaffected.

---

## 9. File I/O

### 9.1 File Declarations
Files are declared as parameters or in a `FILES:` section:

```kobol
FILES:
  CustomerFile AS SEQUENTIAL TEXT RECORD Customer
  OutputFile   AS SEQUENTIAL TEXT RECORD Summary  FOR OUTPUT
  ReportFile   AS INDEXED RECORD Invoice          KEY invoice-id
```

File formats: `SEQUENTIAL`, `INDEXED`, `RELATIVE`.
Encodings: `TEXT` (UTF-8 delimited), `FIXED` (fixed-width), `CSV`, `BINARY`.

### 9.2 File Operations

```kobol
OPEN CustomerFile FOR INPUT
OPEN OutputFile   FOR OUTPUT
OPEN ReportFile   FOR EXTEND    -- append

READ CustomerFile INTO current-customer
  AT END: MOVE TRUE TO end-of-file
END-READ

WRITE OutputFile FROM summary-record

CLOSE CustomerFile
CLOSE OutputFile
```

### 9.3 FOR EACH over a File

```kobol
FOR EACH record IN CustomerFile:
  MOVE record TO current-customer
  PERFORM ProcessCustomer
END-FOR
-- File is automatically closed after the loop
```

---

## 10. Exception Handling

```
TRY ':'
  statement*
(ON exception-type (AS identifier)? ':'
  statement*)+
(ENSURE ':'
  statement*)?
END-TRY
```

Built-in exception types:
- `FILE-NOT-FOUND`
- `IO-ERROR`
- `CONVERSION-ERROR`
- `SIZE-ERROR` (overflow/underflow)
- `APPLICATION-ERROR`
- `JAVA-EXCEPTION` (any uncaught Java exception)

```kobol
TRY:
  OPEN InputFile FOR INPUT
  FOR EACH rec IN InputFile:
    PERFORM ProcessRecord
  END-FOR
ON FILE-NOT-FOUND:
  DISPLAY "ERROR: Input file not found"
  STOP RUN WITH EXIT-CODE 2
ON IO-ERROR AS e:
  DISPLAY "I/O error: " e.message
  RAISE APPLICATION-ERROR "Aborting due to I/O failure"
ENSURE:
  CLOSE InputFile
END-TRY
```

---

## 11. String Operations

Built-in functions for `TEXT` manipulation:

```kobol
COMPUTE full-name  = COMBINE first-name " " last-name
COMPUTE upper-name = UPPERCASE customer-name
COMPUTE lower-name = LOWERCASE customer-name
COMPUTE trimmed    = TRIM input-text
COMPUTE length     = LENGTH full-name
COMPUTE substr     = SUBSTRING full-name FROM 1 FOR 10
COMPUTE pos        = FIND "Smith" IN full-name
COMPUTE rev        = REVERSE full-name
```

### 11.1 String Templates

```kobol
COMPUTE message = "Invoice {invoice-id} for {customer-name}: ${amount}"
```

### 11.2 `SPLIT` — String Splitting

Splits a `TEXT` value by a delimiter into a `LIST OF TEXT`. Inverse of `COMBINE`.

```
split-expr ::= SPLIT text-expr BY string-literal
             | SPLIT text-expr BY string-literal LIMIT integer-literal
```

```kobol
-- Split a CSV line into fields:
LET fields = SPLIT csv-line BY ","
LET first  = fields[1]
LET second = fields[2]

-- Split with a limit (produce at most n parts; last part contains remainder):
LET parts = SPLIT log-line BY ":" LIMIT 3

-- Split a delimited account code:
LET segments = SPLIT account-code BY "-"
-- "GL-001-0042" → ["GL", "001", "0042"]
```

`SPLIT` always returns a `LIST OF TEXT`. An empty input returns a one-element list
containing the empty string. A delimiter not found returns a one-element list
containing the original string.

### 11.3 `REVERSE`

Reverses the character order of a `TEXT` value. Unicode-aware: multi-byte characters
are treated as single units.

```kobol
COMPUTE reversed = REVERSE "Hello"    -- "olleH"
COMPUTE iban-rev = REVERSE iban-code  -- for check-digit validation
```

---

## 12. Numeric Functions

All `DECIMAL` and `MONEY` values are backed by `java.math.BigDecimal` — exact
arbitrary-precision fixed-point arithmetic with no floating-point error.

### 12.1 Basic Numeric Functions

```kobol
COMPUTE abs-val  = ABS negative-number
COMPUTE rounded  = ROUND amount TO 2
COMPUTE truncd   = TRUNCATE amount TO 0
COMPUTE maximum  = MAX value-a, value-b
COMPUTE minimum  = MIN value-a, value-b
COMPUTE r        = MOD total BY divisor
COMPUTE root     = SQRT amount
COMPUTE raised   = POWER base BY exponent
COMPUTE s        = SIGN balance            -- returns -1, 0, or 1
COMPUTE lo       = FLOOR rate
COMPUTE hi       = CEIL  rate
```

### 12.2 Rounding Modes

The default rounding mode is **HALF-EVEN** (banker's rounding, ISO 4217 compliant).
All modes from `java.math.RoundingMode` are available via the `USING` clause:

```kobol
-- Default (HALF-EVEN): 2.545 → 2.54, 2.535 → 2.54
COMPUTE result = ROUND amount TO 2

-- Explicit mode:
COMPUTE result = ROUND amount TO 2 USING HALF-EVEN    -- banker's rounding
COMPUTE result = ROUND amount TO 2 USING HALF-UP      -- commercial: 0.5 rounds away from zero
COMPUTE result = ROUND amount TO 2 USING HALF-DOWN    -- 0.5 rounds toward zero
COMPUTE result = ROUND amount TO 0 USING UP           -- always away from zero (ceiling for positive)
COMPUTE result = ROUND amount TO 0 USING DOWN         -- truncate (toward zero)
COMPUTE result = ROUND amount TO 0 USING CEILING      -- toward positive infinity
COMPUTE result = ROUND amount TO 0 USING FLOOR        -- toward negative infinity
```

| Mode | Description | JVM mapping |
|------|-------------|-------------|
| `HALF-EVEN` | Tie-breaks toward even digit (default) | `RoundingMode.HALF_EVEN` |
| `HALF-UP` | Tie-breaks away from zero | `RoundingMode.HALF_UP` |
| `HALF-DOWN` | Tie-breaks toward zero | `RoundingMode.HALF_DOWN` |
| `UP` | Always away from zero | `RoundingMode.UP` |
| `DOWN` | Truncate toward zero | `RoundingMode.DOWN` |
| `CEILING` | Toward positive infinity | `RoundingMode.CEILING` |
| `FLOOR` | Toward negative infinity | `RoundingMode.FLOOR` |

Division (`/`) uses `HALF-EVEN` by default. Override per-expression:
```kobol
COMPUTE unit-price = total-cost DIVIDE-USING HALF-UP BY quantity
```

### 12.3 Precision and Scale Inspection

```kobol
COMPUTE s = SCALE OF balance          -- number of fractional decimal places
COMPUTE p = PRECISION OF balance      -- total count of significant digits

-- Example:
DATA:
  rate : DECIMAL(10,4) = 3.1415
COMPUTE s = SCALE OF rate             -- 4
COMPUTE p = PRECISION OF rate         -- 10
```

`SCALE OF` and `PRECISION OF` return `INTEGER`. They call `BigDecimal.scale()` and
`BigDecimal.precision()` respectively at the bytecode level.

### 12.4 Precision Context Block

For a block of related calculations that require a specific precision or rounding mode,
use `WITH PRECISION`:

```
precision-block ::= WITH PRECISION integer-literal (ROUNDING rounding-mode)? ':'
                      statement*
                    END-WITH

rounding-mode   ::= HALF-EVEN | HALF-UP | HALF-DOWN | UP | DOWN | CEILING | FLOOR
```

```kobol
-- All divisions and multiplications in this block use DECIMAL128 precision:
WITH PRECISION 34 ROUNDING HALF-EVEN:
  COMPUTE sub-total   = quantity * unit-price
  COMPUTE tax-amount  = sub-total * tax-rate / 100
  COMPUTE grand-total = sub-total + tax-amount
END-WITH

-- Tax prorating with commercial rounding:
WITH PRECISION 10 ROUNDING HALF-UP:
  COMPUTE per-item-tax = total-tax / item-count
  COMPUTE adjusted     = ROUND per-item-tax TO 2
END-WITH
```

The precision context compiles to a `java.math.MathContext(n, RoundingMode.x)` applied
to each intermediate `BigDecimal` operation inside the block. Outside the block, the
field's declared type governs precision (e.g., `DECIMAL(10,2)` caps at 10 total digits
with 2 fractional).

**Standard presets** (usable by name instead of a literal):

| Name | Precision | Rounding | Use case |
|------|-----------|----------|----------|
| `PRECISION DECIMAL32` | 7 | HALF-EVEN | Low-precision / sensor data |
| `PRECISION DECIMAL64` | 16 | HALF-EVEN | General business arithmetic |
| `PRECISION DECIMAL128` | 34 | HALF-EVEN | High-precision financial (default for `SQRT`, `POWER`) |
| `PRECISION UNLIMITED` | unlimited | N/A | Exact integer arithmetic; division must produce whole number |

```kobol
WITH PRECISION DECIMAL128:
  COMPUTE compound = principal * POWER(1 + rate/100 BY periods)
END-WITH
```

### 12.5 SUM Pipeline and Precision

The `SUM` pipeline stage over a `LIST OF DECIMAL` or `LIST OF MONEY` uses
`BigDecimal.add` in a precision-safe reduce — no floating-point conversion:

```kobol
COMPUTE total =
  invoice-list
    FILTER WHERE NOT paid
    TRANSFORM TO amount
    SUM                  -- exact BigDecimal reduce; result type is MONEY/DECIMAL
```

`SUM` over `LIST OF INTEGER` uses long addition. The result type inherits the element
type of the collection.

---

## 13. Collection Operations

### 13.1 LIST Operations

```kobol
ADD invoice TO invoice-list               -- append
COMPUTE count = LENGTH invoice-list       -- element count (also: invoice-list.LENGTH)

-- Pipeline operations
COMPUTE unpaid-total =
  invoice-list
    FILTER WHERE NOT paid
    TRANSFORM TO amount
    SUM

COMPUTE top-invoices =
  invoice-list
    FILTER WHERE amount > 10000
    SORT BY amount DESCENDING
    TAKE 10
```

> `LIST` has no prepend (`ADD … AT FIRST`) or element-removal (`REMOVE … FROM <list>`)
> operator; both are rejected at compile time, not silently accepted. Use `ADD` (append)
> or pipeline operations.

### 13.2 MAP Operations

```kobol
PUT "active" TO status-map WITH KEY "A"
GET status-map KEY "A" INTO description
COMPUTE map-size = LENGTH status-map      -- entry count (also: status-map.LENGTH)
```

> `MAP` has no key-removal (`REMOVE <map> KEY k`) operator; it is rejected at compile time.

---

## 14. Module System

```
module-decl  ::= MODULE identifier ':'
                   (record-decl | constant-section | procedure-decl)*
                 END-MODULE
```

```kobol
-- file: billing-utils.kbl
MODULE BillingUtils:
  DEFINE STANDARD-TAX-RATE : DECIMAL = 8.5
  DEFINE LATE-FEE-RATE     : DECIMAL = 1.5

  PROCEDURE CalculateTax USING amount : MONEY(12.2) RETURNING MONEY(12.2):
    COMPUTE result = amount * STANDARD-TAX-RATE / 100
    RETURN result
  END-PROCEDURE
END-MODULE
```

```kobol
-- consuming program
IMPORT billing-utils.BillingUtils AS Utils

PROCEDURE ProcessInvoice:
  COMPUTE tax-due = Utils.CalculateTax(invoice.amount)
  ADD tax-due TO invoice.amount
END-PROCEDURE
```

---

## 15. Full Program Example

```kobol
PROGRAM MonthlyBillingRun
  VERSION "2.1"
  AUTHOR  "Finance Systems Team"

IMPORT java.time.LocalDate
IMPORT java.time.format.DateTimeFormatter

RECORD Invoice:
  invoice-id   : INTEGER
  customer-id  : INTEGER
  customer-name: TEXT(100)
  amount       : MONEY(12.2)
  due-date     : DATE
  paid         : BOOLEAN
  status-code  : TEXT(1)
    CONDITION Pending  WHEN status-code = "P"
    CONDITION Paid     WHEN status-code = "X"
    CONDITION Overdue  WHEN status-code = "O"

RECORD BillingResult:
  total-invoices    : INTEGER
  paid-count        : INTEGER
  unpaid-count      : INTEGER
  total-amount      : MONEY(14.2)
  total-paid        : MONEY(14.2)
  total-outstanding : MONEY(14.2)

FILES:
  InvoiceFile AS SEQUENTIAL CSV RECORD Invoice
  ReportFile  AS SEQUENTIAL TEXT RECORD BillingResult FOR OUTPUT

DEFINE LATE-FEE-RATE : DECIMAL = 1.5
DEFINE REPORT-HEADER : TEXT    = "=== Monthly Billing Report ==="

DATA:
  current-invoice : Invoice
  billing-result  : BillingResult
  process-date    : DATE
  end-of-file     : BOOLEAN = FALSE

PROCEDURE Main:
  CALL LocalDate.now GIVING process-date
  DISPLAY REPORT-HEADER
  DISPLAY "Processing date: " process-date

  TRY:
    PERFORM ProcessAllInvoices
    PERFORM WriteReport
    PERFORM DisplaySummary
  ON FILE-NOT-FOUND:
    DISPLAY "ERROR: Invoice file not found"
    STOP RUN WITH EXIT-CODE 1
  ON IO-ERROR AS e:
    DISPLAY "I/O ERROR: " e.message
    STOP RUN WITH EXIT-CODE 2
  END-TRY

  DISPLAY "Billing run complete."
  STOP RUN
END-PROCEDURE

PROCEDURE ProcessAllInvoices:
  MOVE 0 TO billing-result.total-invoices
  MOVE 0 TO billing-result.total-amount
  OPEN InvoiceFile FOR INPUT
  FOR EACH inv-record IN InvoiceFile:
    MOVE inv-record TO current-invoice
    PERFORM ProcessSingleInvoice
  END-FOR
END-PROCEDURE

PROCEDURE ProcessSingleInvoice:
  ADD 1 TO billing-result.total-invoices
  ADD current-invoice.amount TO billing-result.total-amount

  IF current-invoice.Paid:
    ADD 1                            TO billing-result.paid-count
    ADD current-invoice.amount       TO billing-result.total-paid
  ELSE:
    ADD 1                            TO billing-result.unpaid-count
    ADD current-invoice.amount       TO billing-result.total-outstanding
    IF current-invoice.Overdue:
      PERFORM ApplyLateFee
    END-IF
  END-IF
END-PROCEDURE

PROCEDURE ApplyLateFee:
  COMPUTE late-fee = current-invoice.amount * LATE-FEE-RATE / 100
  ADD late-fee TO current-invoice.amount
  DISPLAY "Late fee applied to invoice " current-invoice.invoice-id ": " late-fee
END-PROCEDURE

PROCEDURE WriteReport:
  OPEN ReportFile FOR OUTPUT
  WRITE ReportFile FROM billing-result
  CLOSE ReportFile
END-PROCEDURE

PROCEDURE DisplaySummary:
  DISPLAY ""
  DISPLAY "--- Summary ---"
  DISPLAY "Total invoices : " billing-result.total-invoices
  DISPLAY "Paid           : " billing-result.paid-count
  DISPLAY "Unpaid         : " billing-result.unpaid-count
  DISPLAY "Total amount   : " billing-result.total-amount
  DISPLAY "Total paid     : " billing-result.total-paid
  DISPLAY "Outstanding    : " billing-result.total-outstanding
END-PROCEDURE
```

---

## 16. Grammar (EBNF Summary)

```ebnf
program          = program-header import* record-decl* constant* data-section? procedure+

program-header   = "PROGRAM" IDENT ("VERSION" STRING)? ("AUTHOR" STRING)?

import           = "IMPORT" qualified-name ("AS" IDENT)?
qualified-name   = IDENT ("." IDENT)*

record-decl      = "RECORD" IDENT ":" field-decl+ "END-RECORD"?
field-decl       = IDENT ":" type-spec condition-decl*
condition-decl   = "CONDITION" IDENT "WHEN" expression

constant         = "DEFINE" IDENT ":" type-spec "=" literal

data-section     = "DATA" ":" data-item+
data-item        = IDENT ":" type-spec ("=" literal)?

type-spec        = "INTEGER" | "SMALLINT"
                 | "DECIMAL" "(" INT "," INT ")"
                 | "MONEY" "(" INT "." INT ")"
                 | "TEXT" ("(" INT ")")?
                 | "BOOLEAN" | "DATE" | "TIME" | "DATETIME"
                 | "LIST" "OF" type-spec
                 | "MAP" "OF" type-spec "TO" type-spec
                 | IDENT

procedure        = "PROCEDURE" IDENT ("USING" params)? ("RETURNING" type-spec)?
                   ":" statement* "END-PROCEDURE"?

params           = param ("," param)*
param            = IDENT ":" type-spec

statement        = move-stmt | compute-stmt | add-stmt | subtract-stmt
                 | multiply-stmt | divide-stmt | display-stmt | perform-stmt
                 | if-stmt | while-stmt | for-each-stmt | repeat-stmt
                 | open-stmt | read-stmt | write-stmt | close-stmt
                 | try-stmt | raise-stmt | return-stmt | stop-stmt | call-stmt

expression       = or-expr
or-expr          = and-expr ("OR" and-expr)*
and-expr         = not-expr ("AND" not-expr)*
not-expr         = "NOT" not-expr | compare-expr
compare-expr     = add-expr (("=" | "<>" | "<" | ">" | "<=" | ">=") add-expr)?
add-expr         = mul-expr (("+" | "-") mul-expr)*
mul-expr         = power-expr (("*" | "/") power-expr)*
power-expr       = unary-expr ("**" unary-expr)?
unary-expr       = "-" unary-expr | primary
primary          = literal | IDENT ("." IDENT)* | "(" expression ")"
                 | builtin-function | condition-name
```

---

## 17. Developer Ergonomics

This section defines the features that make **Kobol** pleasant to work with daily.
Ergonomics are a first-class design goal, not an afterthought.

---

### 17.1 String Interpolation

Embed expressions directly in string literals using `{expr}`. No more multi-value `DISPLAY` chains.

```kobol
-- Instead of:
DISPLAY "Invoice " invoice-id " for " customer-name ": $" amount

-- Write:
DISPLAY "Invoice {invoice-id} for {customer-name}: ${amount}"
```

Any expression works inside `{}`:
```kobol
DISPLAY "Tax ({TAX-RATE}%): ${amount * TAX-RATE / 100}"
DISPLAY "Status: {IF paid THEN \"Paid\" ELSE \"Unpaid\"}"
```

**Nested string literals inside `{…}`.** An interpolation body is a full expression, so it
may contain its own string literals. Write them either with plain quotes or, since the
whole literal already sits inside `"…"`, with escaped quotes `\"…\"` — both lex identically:
```kobol
DISPLAY "val {UPPERCASE "hi"}"          -- plain quotes
DISPLAY "val {UPPERCASE \"hi\"}"        -- escaped quotes — same result
```

Interpolation also works in `COMPUTE` assignments:
```kobol
COMPUTE subject-line = "Overdue notice: Invoice {invoice-id} — {customer-name}"
```

**Literal braces — `\{` and `\}`.** Because `{` always opens an interpolation, write a
literal brace with the escapes `\{` and `\}`. This is the escape hatch for JSON literals
and regex `{n,m}` quantifiers:
```kobol
LET payload = "\{\"id\":{invoice-id}\}"          -- → {"id":42}
VALIDATE code MUST MATCH "[A-Z0-9]\{8,20\}"      -- literal regex quantifier
```
The full escape set inside a `"…"` string is `\n`, `\t`, `\\`, `\"`, `\{`, `\}`. A bare
`{` still starts interpolation (backward-compatible). Single-quoted raw strings (`'…'`)
never interpolate, so braces there are already literal.

---

### 17.2 LET — Short Form for COMPUTE

`LET` is a direct alias for `COMPUTE`. Use it when the expression-assignment intent is
clearer than arithmetic. Both forms are identical; choose the one that reads better.

```kobol
-- Full form (arithmetic context)
COMPUTE net-pay = gross-pay - (gross-pay * TAX-RATE / 100)

-- Short form (assignment context)
LET full-name = "{first-name} {last-name}"
LET is-premium = segment = "PREMIUM" AND balance > 10000
LET report-title = "Monthly Report — {month-name} {year}"
```

---

### 17.3 Inline Procedure-Local Variables

Variables can be declared inside a `PROCEDURE` body without needing to appear in the
global `DATA:` section. Local variables are scoped to the procedure.

```kobol
PROCEDURE CalculateInvoiceTax USING inv : Invoice RETURNING MONEY(12.2):
  LET tax-rate   : DECIMAL     = 8.5
  LET base       : MONEY(12.2) = inv.amount
  LET tax-amount : MONEY(12.2) = base * tax-rate / 100
  RETURN tax-amount
END-PROCEDURE
```

Type annotation is optional when the type can be inferred from the right-hand side:
```kobol
PROCEDURE BuildGreeting USING name : TEXT RETURNING TEXT:
  LET greeting = "Hello, {name}!"    -- inferred as TEXT
  LET length   = LENGTH greeting     -- inferred as INTEGER
  RETURN greeting
END-PROCEDURE
```

---

### 17.4 Type Inference in DATA Section

When an initializer is provided, the type annotation in `DATA:` is optional:

```kobol
DATA:
  tax-rate     = 8.5          -- inferred: DECIMAL
  app-name     = "Kobol App"  -- inferred: TEXT
  record-count = 0            -- inferred: INTEGER
  is-running   = TRUE         -- inferred: BOOLEAN
  invoices     = []           -- inferred: LIST OF (element type resolved from first ADD)
```

Explicit types are still required for `MONEY` and `DECIMAL(p,s)` to preserve precision:
```kobol
DATA:
  balance : MONEY(12.2) = 0    -- explicit: precision required
```

---

### 17.5 Script Mode (No Program Header)

For small utilities and quick scripts, the `PROGRAM` header and `PROCEDURE Main:` wrapper
are optional. Top-level statements are valid at the file level in script mode.

```kobol
-- greet.kbl  (script mode — no PROGRAM header needed)
LET name = "World"
DISPLAY "Hello, {name}!"
DISPLAY "Running on Kobol {KOBOL-VERSION}"
```

Script mode is detected automatically when there is no `PROGRAM` keyword. The entire file
body is treated as the implicit main procedure.

---

### 17.6 DO as Synonym for PERFORM

`DO` is an alias for `PERFORM`, offering a shorter, natural-language alternative:

```kobol
DO ValidateRecord
DO ApplyTax USING current-invoice
```

Both `PERFORM` and `DO` are valid throughout. Use whichever reads better in context.

---

### 17.7 DISPLAY Formatting

#### Automatic Alignment

`DISPLAY TABLE` renders a `LIST OF Record` as a formatted table to stdout:

```kobol
DISPLAY TABLE invoice-list
-- Output:
-- INVOICE-ID  CUSTOMER-NAME          AMOUNT      PAID
-- ----------  ---------------------  ----------  ----
-- 1001        Acme Corp              12,500.00   No
-- 1002        Globex                  3,200.50   Yes
```

#### Number Formatting

```kobol
DISPLAY FORMAT "#,##0.00"  total-amount    -- 1,234,567.89
DISPLAY FORMAT "0000"      invoice-id      -- 0042
DISPLAY FORMAT "DD/MM/YYYY" due-date       -- 31/12/2026
```

#### Indented / Labelled Output

```kobol
DISPLAY LABEL "Total invoices"  invoice-count
DISPLAY LABEL "Outstanding"     outstanding-amount
-- Output:
-- Total invoices : 142
-- Outstanding    : 8,920.50
```

---

### 17.8 Rich Diagnostic Messages

Every **Kobol** compiler error must:
1. Show the exact source line with a `^` pointer to the problem
2. Include a plain-English explanation of what went wrong
3. Where possible, include a "did you mean?" suggestion

**Example — undefined variable:**
```
error[E001]: Undefined variable 'inovice-total'
  --> billing.kbl:42:10
   |
42 |   ADD tax TO inovice-total
   |              ^^^^^^^^^^^^^ not found in this scope
   |
   = did you mean: invoice-total (declared at line 8)?
```

**Example — type mismatch:**
```
error[E012]: Type mismatch in ADD statement
  --> billing.kbl:55:7
   |
55 |   ADD customer-name TO total-amount
   |       ^^^^^^^^^^^^^ TEXT cannot be added to MONEY(12.2)
   |
   = hint: use COMPUTE total-amount = total-amount + TO-DECIMAL(customer-name)
           if the field contains a numeric string
```

**Example — missing END-IF:**
```
warning[W003]: Missing END-IF terminator
  --> billing.kbl:60:3
   |
60 |   IF balance > 0:
   |   ^^ this IF block opened here has no END-IF
   |
   = note: END-IF is optional but recommended for blocks longer than 3 lines
```

---

### 17.9 REPL (Interactive Shell)

**Kobol** ships with a REPL for interactive exploration. This is the fastest path from idea to
working logic — no file, no build step.

```
$ kobol-repl
Kobol REPL 0.1.0 — type :help for commands, :quit to exit
kobol> LET amount : MONEY(12.2) = 5000.00
=> 5000.00
kobol> COMPUTE tax = amount * 8.5 / 100
=> 425.00
kobol> DISPLAY "Total: {amount + tax}"
Total: 5425.00
kobol> RECORD Invoice: id : INTEGER, customer : TEXT, amount : MONEY(12.2)
=> Record 'Invoice' defined (3 fields)
kobol> LET inv : Invoice = Invoice { id: 1, customer: "Acme", amount: 1000.00 }
=> Invoice(id=1, customer=Acme, amount=1000.00)
kobol> :history       -- show statement history
kobol> :save draft.kbl  -- save session to file
kobol> :quit
```

REPL commands:
| Command | Action |
|---------|--------|
| `:quit` / `:q` | Exit |
| `:help` | Print help |
| `:history` | Show statement history |
| `:clear` | Reset all declarations |
| `:save <file>` | Write session to a `.kbl` file |
| `:load <file>` | Load and execute a `.kbl` file |
| `:type <expr>` | Show the inferred type of an expression |

---

### 17.10 Watch Mode

`kobolc --watch source.kbl` monitors the file for changes and recompiles immediately,
printing errors inline. Designed for rapid iteration during development.

```
$ kobolc --watch invoice-processor.kbl
[kobolc] Watching invoice-processor.kbl...
[kobolc] ✓ Compiled successfully (0 errors, 0 warnings)
[kobolc] Change detected — recompiling...
[kobolc] ✗ 1 error:
error[E001]: Undefined variable 'invoce-id'
  --> invoice-processor.kbl:34:14
   = did you mean: invoice-id?
[kobolc] Change detected — recompiling...
[kobolc] ✓ Compiled successfully (0 errors, 0 warnings)
```

---

### 17.11 Struct Literals (Record Initializers)

Construct a record value inline without `MOVE`-ing each field separately:

```kobol
-- Instead of:
MOVE 1001          TO inv.invoice-id
MOVE "Acme Corp"   TO inv.customer-name
MOVE 5000.00       TO inv.amount
MOVE FALSE         TO inv.paid

-- Write:
LET inv = Invoice {
  invoice-id    : 1001
  customer-name : "Acme Corp"
  amount        : 5000.00
  paid          : FALSE
}
```

Unspecified fields receive their type defaults. Extra fields are a compile-time error.

---

### 17.12 Named Arguments in PERFORM / DO

When calling procedures with multiple parameters, names improve clarity:

```kobol
-- Positional (order must match declaration):
DO SendNotice USING customer-name, email-address, invoice-total

-- Named (order-independent, self-documenting):
DO SendNotice USING
  name    : customer-name
  email   : email-address
  amount  : invoice-total
```

---

### 17.13 English-like Syntax

**Kobol**'s most fundamental ergonomic design principle is that programs should read like
business prose, not like source code. Every language construct is chosen to minimise
cognitive translation between the problem domain and the program text.

**Business-domain verbs and control flow:**
```kobol
-- Arithmetic reads as intent, not machine operations:
ADD monthly-fee    TO total-charges
SUBTRACT discount  FROM invoice-amount
MULTIPLY quantity  BY unit-price GIVING line-total
DIVIDE total-sales BY item-count GIVING average-sale

-- Named conditions capture business rules:
CONDITION Customer-Active    WHEN status = "ACTIVE"
CONDITION High-Value-Invoice WHEN amount > 10000
CONDITION Overdue            WHEN due-date < TODAY AND NOT paid

IF Customer-Active AND High-Value-Invoice:
  DO SendPremiumNotice USING customer
END-IF

-- Loops that mirror business descriptions:
FOR EACH invoice IN unpaid-invoices:
  DO ApplyLateFee USING invoice
END-FOR

REPEAT 3 TIMES:
  DO RetryPayment USING transaction
END-REPEAT

WHILE queue IS NOT EMPTY:
  DO ProcessNext USING queue
END-WHILE

-- PERFORM and its synonym DO read as natural delegation:
PERFORM GenerateMonthlyReport
DO GenerateMonthlyReport        -- identical semantics

-- Named arguments read as self-documenting prose:
DO SendNotice USING
  name    : customer-name
  email   : email-address
  amount  : invoice-total

-- Program termination reads clearly:
STOP RUN
STOP RUN WITH EXIT-CODE 1
```

**Natural-language boolean expressions:**
```kobol
IF balance IS POSITIVE:
IF account-status IS NOT EMPTY:
IF invoice-count = 0:            -- "if there are no invoices"
IF results IS EMPTY:
```

**The result** is that non-programmer business stakeholders can read **Kobol** source code
and verify that it expresses the correct business rules — a property deliberately
inherited from COBOL and elevated with modern readability improvements.

---

### 17.14 Multi-line `NOTE:` Block Comments

In addition to the `--` single-line comment (§1.6), **Kobol** supports `NOTE:` … `END-NOTE`
block comments for longer explanations, file headers, and temporarily disabling code.
The form mirrors **Kobol**'s other `X:` … `END-X` blocks (PROCEDURE/TRY/RECORD) — english,
not C-style `/* */`. Everything between the opening `NOTE:` line and the line whose first
word is `END-NOTE` is free text; the lexer skips it without tokenizing.

```kobol
NOTE:
  Invoice Processing Pipeline
  --------------------------
  1. Validate each invoice against business rules
  2. Apply applicable tax rates from CONFIG
  3. Write processed records to the output file
  4. Log summary statistics at INFO level
END-NOTE
PROGRAM InvoiceProcessor

NOTE:
  Temporarily disabled:
  debug-counter : INTEGER = 0
END-NOTE
DATA:
  total-processed : INTEGER = 0
```

`NOTE:` is recognized only as the first token of a line, so `NOTE` remains usable as an
ordinary identifier elsewhere. An unterminated `NOTE:` block (no `END-NOTE`) is a
compile-time error with a precise source location.

**Tagged single-line comments.** A `--` comment whose first word is `TODO`, `FIXME`,
`HACK`, or `XXX` is surfaced as a compiler diagnostic (TODO/HACK → info, FIXME/XXX →
warning), so they show up in build output and can be gated in CI:

```kobol
-- TODO: rescale before store
-- FIXME: O(n^2) here, switch to a MAP
```

The legacy C-style `/* */` block-comment form is not part of the language.

---

### 17.15 UUID Type

The `UUID` type represents a universally unique identifier (128-bit, RFC 4122 format).
It compiles to `java.util.UUID` on the JVM.

```
type-spec  ::= ... | UUID
uuid-expr  ::= UUID-GENERATE()
             | UUID-FROM-TEXT(string-expr)
             | UUID-TO-TEXT(uuid-expr)
             | UUID-NIL()
```

> UUID generators/parsers are builtin **functions** and use call syntax with parentheses
> (`UUID-GENERATE()`), consistent with all other builtins. A bare `UUID-GENERATE` is an
> undefined name.

**Declaration and usage:**
```kobol
DATA:
  transaction-id  : UUID
  correlation-id  : UUID

PROCEDURE StartTransaction:
  -- Generate a new random UUID (v4):
  LET transaction-id = UUID-GENERATE()

  -- Parse a UUID from an externally supplied string:
  LET correlation-id = UUID-FROM-TEXT(request-header-id)

  LOG INFO "Transaction started" WITH
    tx-id  : transaction-id
    corr   : correlation-id
END-PROCEDURE
```

**Comparison and display:**
```kobol
-- UUIDs compare with = and <> (by value, via java.util.UUID.equals):
IF transaction-id <> UUID-NIL():
  DO ProcessTransaction USING transaction-id
END-IF

-- DISPLAY renders the standard hyphenated form:
DISPLAY transaction-id   -- "550e8400-e29b-41d4-a716-446655440000"

-- String conversion for external systems:
LET id-text = UUID-TO-TEXT(transaction-id)
```

**Built-in functions:**

| Expression | Result |
|------------|--------|
| `UUID-GENERATE()` | New random UUID (v4) |
| `UUID-FROM-TEXT(expr)` | Parse UUID from `TEXT`; TypeChecker error if format cannot be verified at compile time |
| `UUID-NIL()` | The nil UUID (`00000000-0000-0000-0000-000000000000`) |
| `UUID-TO-TEXT(uuid-expr)` | Convert UUID to its hyphenated string representation |

**Type rules:**
- `UUID` fields compare with `=` and `<>`; ordering operators (`<`, `>`) are not supported.
- `UUID` is never implicitly coerced to or from `TEXT`; use `UUID-FROM-TEXT` and `UUID-TO-TEXT` explicitly.
- `TEXT SENSITIVE` can be combined with `UUID` for correlation IDs that must not appear in logs.

**JVM mapping:** `UUID-GENERATE` → `java.util.UUID.randomUUID()`;
`UUID-FROM-TEXT` → `java.util.UUID.fromString()`; `UUID-TO-TEXT` → `java.util.UUID.toString()`.

---

### 17.16 Large-Precision Fixed-Point Decimal Arithmetic

**Kobol** treats exact numeric correctness as a language-level guarantee, not an
implementation detail. Floating-point (`float`, `double`) is never used for
`DECIMAL` or `MONEY` values — all arithmetic is exact `BigDecimal` throughout
the compiler, emitter, and standard library.

**Why this matters in business systems:**
```kobol
-- IEEE 754 double: 0.1 + 0.2 = 0.30000000000000004
-- Kobol DECIMAL:  0.1 + 0.2 = 0.3  (exact)

DATA:
  item-a : DECIMAL(10,2) = 0.10
  item-b : DECIMAL(10,2) = 0.20
COMPUTE total = item-a + item-b    -- always 0.30, never 0.30000000000000004
```

**Declaration — precision and scale are explicit:**
```kobol
DATA:
  unit-price   : DECIMAL(12,4)    -- up to 99999999.9999
  total-amount : MONEY(14,2)      -- up to 999999999999.99
  exchange-rate: DECIMAL(18,8)    -- 8 decimal places for FX rates
  pi-approx    : DECIMAL(38,20)   -- 20-digit scientific precision
```

**Rounding modes — full control, no surprises:**
```kobol
-- Financial (half-even, ISO 4217):
COMPUTE tax = ROUND amount * rate / 100 TO 2

-- Commercial (half-up, everyday expectation):
COMPUTE invoice-total = ROUND sub-total TO 2 USING HALF-UP

-- Tax prorate — truncate to avoid over-collection:
COMPUTE per-unit = ROUND total-tax / units TO 4 USING DOWN
```

**Precision context for compound expressions:**
```kobol
WITH PRECISION DECIMAL128:
  COMPUTE npv =
    cash-flows
      TRANSFORM TO amount
      SUM
      DIVIDE-USING HALF-EVEN BY POWER(1 + discount-rate BY periods)
END-WITH
```

**Scale and precision inspection at runtime:**
```kobol
COMPUTE stored-scale     = SCALE OF rate          -- fractional digits
COMPUTE stored-precision = PRECISION OF rate      -- total significant digits
```

**Pipeline SUM is exact:**
The `SUM` pipeline stage over `LIST OF DECIMAL` or `LIST OF MONEY` uses
`BigDecimal.add` — no lossy `doubleValue()` conversion at any point.

---

## 18. Concurrency

**Kobol** concurrency is backed by **JVM virtual threads** (Project Loom, standard since Java 21).
Programs look synchronous; the runtime executes concurrent branches on lightweight virtual
threads with no manual thread-pool sizing.

### 18.1 `CONCURRENT` Block

```
concurrent-block ::= CONCURRENT ':'
                        (DO proc-call)*
                      WAIT (ALL | FIRST | n)
```

```kobol
PROCEDURE FetchAllData:
  CONCURRENT:
    DO FetchCustomers
    DO FetchInvoices
    DO FetchPricingRules
  WAIT ALL
  DO BuildReport
END-PROCEDURE
```

`WAIT ALL`   — all branches must complete successfully.
`WAIT FIRST` — return as soon as the first branch completes; cancel remaining.
`WAIT 2`     — wait until 2 of N branches succeed; cancel the rest.

### 18.2 Structured Concurrency Scope

```kobol
CONCURRENT SCOPE "payment-pipeline":
  DO ValidateOrder
  DO CheckInventory
  DO AuthorisePayment
WAIT ALL OR FAIL
```

If any branch raises an exception, the scope cancels remaining branches and re-raises
the first exception. The scope name appears in log output for traceability.

### 18.3 Parallel `FOR EACH`

```kobol
FOR EACH inv IN InvoiceFile PARALLEL MAX-THREADS 8:
  DO ProcessInvoice USING inv
END-FOR
```

`PARALLEL` — each iteration runs on a separate virtual thread.
`MAX-THREADS n` — optional upper bound; defaults to system CPU count × 2.
Iteration order of side effects is unspecified; use `WAIT ALL` semantics implicitly.

### 18.4 `ASYNC PROCEDURE` and `AWAIT`

```kobol
ASYNC PROCEDURE FetchExchangeRate USING currency : TEXT RETURNING DECIMAL:
  CALL http.GET USING "https://api.rates.io/{currency}" GIVING response
  RETURN response.body.rate AS DECIMAL
END-PROCEDURE

PROCEDURE ConvertAmount:
  DATA:
    rate-task : FUTURE OF DECIMAL
    rate      : DECIMAL(10, 2)
  PERFORM FetchExchangeRate USING "EUR" GIVING rate-task
  -- do other work here; rate-task runs concurrently
  AWAIT rate-task INTO rate
  COMPUTE converted = amount * rate
END-PROCEDURE
```

Launching an async procedure uses `PERFORM … GIVING <future>` — the `GIVING` target
must be declared `FUTURE OF T`, where `T` is the procedure's `RETURNING` type (a non-`FUTURE`
target is `E216`; `GIVING` on a *synchronous* `PERFORM` is `E215` — see §8.5). The procedure
body runs concurrently on a virtual thread, returning a `FUTURE OF T` handle immediately.
`AWAIT <future> INTO <var>` blocks until the future completes and stores its value into
`<var>`. This works identically for a module-qualified async `PERFORM` (see §8.5).

### 18.5 Thread Safety of DATA Section

- DATA section fields accessed inside `CONCURRENT` blocks produce a **compiler warning**
  if any branch writes to the same field.
- Use procedure parameters and `RETURNING` to pass data between branches.
- Shared accumulation: use `ATOMIC ADD value TO shared-counter`.

---

## 19. Security Primitives

### 19.1 `VALIDATE` Statement

Declarative input validation. A failed `MUST` clause raises `KobolValidationError`
with the field name, offending value, and constraint description.

```
validate-stmt ::= VALIDATE expr ':'
                    (MUST constraint (FAIL-MSG literal)?)*
```

```kobol
PROCEDURE ProcessPayment USING amount : MONEY(12,2), account-id : TEXT(20):
  VALIDATE amount:
    MUST BE > 0              FAIL-MSG "Amount must be positive"
    MUST BE <= 1000000       FAIL-MSG "Exceeds single-transaction limit"
  VALIDATE account-id:
    MUST NOT BE EMPTY
    MUST MATCH "[A-Z0-9]\{8,20\}"  FAIL-MSG "Invalid account ID format"
    MUST LENGTH >= 8
END-PROCEDURE
```

Supported constraints:

| Constraint | Applies To |
|-----------|-----------|
| `BE > / >= / < / <= / = value` | Numeric |
| `MATCH "regex"` | Text |
| `NOT BE EMPTY / BLANK` | Text |
| `LENGTH >= / <= n` | Text |
| `BE POSITIVE-INTEGER / POSITIVE-DECIMAL` | Numeric |
| `BE VALID-DATE` | Text → Date check |
| `BE IN value1, value2, ...` | Any scalar |
| `SATISFY ProcedureName` | Any (custom validator) |

### 19.2 `TEXT SENSITIVE` Fields

```kobol
DATA:
  card-number  : TEXT(16) SENSITIVE
  cvv-code     : TEXT(4)  SENSITIVE
  customer-pin : TEXT(6)  SENSITIVE
```

- Redacted (`***REDACTED***`) in all `LOG` output.
- Excluded from `DISPLAY STRUCTURED` / JSON serialisation unless `INCLUDING SENSITIVE`.
- Memory zeroed when the variable leaves scope (best-effort via JVM finalizer).

### 19.3 Encryption

Sensitive fields can be encrypted at the language level. The JVM's `javax.crypto`
layer handles the cryptographic operations; **Kobol** exposes the algorithm and key
source explicitly so every encryption decision is auditable.

**Field-level encryption:**
```kobol
DATA:
  pan-number     : TEXT(19) SENSITIVE ENCRYPTED USING AES-256-GCM KEY pan-key
  pan-key        : TEXT     SENSITIVE FROM ENV "PAN_ENCRYPTION_KEY"

PROCEDURE StorePAN USING raw-pan : TEXT(19):
  VALIDATE raw-pan:
    MUST MATCH "[0-9]\{13,19\}"
  -- ENCRYPT returns ciphertext; plaintext never written to disk or logs
  MOVE ENCRYPT(raw-pan USING AES-256-GCM KEY pan-key) TO pan-number
  CALL jdbc.execute
    USING  "INSERT INTO cards(pan_encrypted) VALUES (?)"
    PARAMS pan-number
END-PROCEDURE

PROCEDURE LoadPAN USING stored-pan : TEXT RETURNING TEXT(19):
  RETURN DECRYPT(stored-pan USING AES-256-GCM KEY pan-key)
END-PROCEDURE
```

**File-level encryption:**
```kobol
OPEN SensitiveFile FOR OUTPUT ENCRYPTED USING AES-256-GCM KEY file-key
FOR EACH record IN DataList:
  WRITE record TO SensitiveFile
END-FOR
CLOSE SensitiveFile

OPEN SensitiveFile FOR INPUT ENCRYPTED USING AES-256-GCM KEY file-key
FOR EACH record IN SensitiveFile:
  DO ProcessRecord USING record
END-FOR
```

**Key management:**
```kobol
-- From environment variable (recommended for CI/CD):
LET key = KEYVALUE FROM ENV "MY_SECRET_KEY"

-- From a JCEKS / PKCS12 keystore:
LET key = KEYVALUE FROM KEYSTORE "config/keys.p12" ALIAS "app-key" PASSWORD FROM ENV "KS_PASS"
```

Supported algorithms: `AES-256-GCM` (default), `AES-128-GCM`,
`CHACHA20-POLY1305`. Weak algorithms (`DES`, `3DES`, `AES-CBC` without authentication)
are rejected by the TypeChecker with an explanatory error.

### 19.4 Parameterised SQL (Injection Prevention)

The `CALL jdbc.*` bridge never concatenates user data into SQL strings.
The TypeChecker emits a compile-time **error** if string concatenation or interpolation
appears in the `USING` clause of a `CALL jdbc.query` or `CALL jdbc.execute`.

```kobol
-- CORRECT — parameterised:
CALL jdbc.query
  USING  "SELECT * FROM invoices WHERE status = ?"
  PARAMS inv-status
  INTO   results AS LIST OF Invoice

-- COMPILE ERROR — never allowed:
CALL jdbc.query
  USING  "SELECT * FROM invoices WHERE status = '" + inv-status + "'"
```

---

## 20. `CONFIG` Section

The `CONFIG` section separates deployment configuration from program logic.
All bindings are resolved and validated at program startup before any data is processed.

**Resolution order (first value wins):**
1. Process environment variable (`System.getenv`)
2. `.env` file in the working directory (or `-Dkobol.env.file=path`)
3. `DEFAULT` value (if declared)
4. Zero / empty (if neither `REQUIRED` nor `DEFAULT`)

```
config-section ::= CONFIG ':'
                     (identifier ':' type FROM ENV literal
                       (DEFAULT expression)?
                       (REQUIRED)?
                       (MUST constraint)*)*
```

```kobol
CONFIG:
  db-url        : TEXT    FROM ENV "DB_URL"        REQUIRED
  batch-size    : INTEGER FROM ENV "BATCH_SIZE"    DEFAULT 500    MUST BE > 0
  tax-rate      : DECIMAL FROM ENV "TAX_RATE"      DEFAULT 8.5    MUST BE >= 0
  retry-count   : INTEGER FROM ENV "RETRY_COUNT"   DEFAULT 3
  debug-logging : BOOLEAN FROM ENV "DEBUG_LOG"     DEFAULT FALSE
  server-port   : INTEGER FROM ENV "PORT"          DEFAULT 8080
```

- `REQUIRED` — program exits with a clear error listing the missing variable name and
  a hint to create a `.env` file.
- `DEFAULT` — used when the env var is absent in both the process environment and `.env`.
- `MUST` — same constraint syntax as `VALIDATE`; applied after loading.

Config values are accessible anywhere in the program as read-only constants.

### 20.1 `.env` File for Local Development

Create a `.env` file in your project root to avoid setting environment variables
manually during development. It is loaded automatically; production deployments
use real environment variables which take precedence.

```bash
# .env — never commit to version control
DB_URL=postgresql://localhost:5432/myapp
PORT=8080
BATCH_SIZE=100
TAX_RATE=8.5
DEBUG_LOG=true
```

Supported value formats:
- Plain text: `KEY=value`
- Quoted strings (outer quotes stripped): `KEY="value with spaces"`
- Single-quoted: `KEY='value'`
- Boolean: `true / false / yes / no / 1 / 0 / on / off`
- Comments: lines starting with `#` are ignored

Override the file path at runtime:
```bash
java -Dkobol.env.file=/etc/myapp/config.env -jar myapp.jar
```

---

## 21. `VARIANT` Types

Named, sealed union types. The compiler enforces exhaustive matching.

```
variant-decl ::= VARIANT identifier IS
                   identifier (WITH (identifier ':' type)*)?
                   ('|' identifier (WITH (identifier ':' type)*)?)*
```

```kobol
VARIANT OrderStatus IS
  Pending
  | Active  WITH order-date : DATE
  | Shipped WITH ship-date  : DATE, tracking : TEXT
  | Closed  WITH close-date : DATE, reason   : TEXT
```

Construction:
```kobol
MOVE Pending TO current-status                         -- nullary case: bare name (no parens)
MOVE Active(order-date: TODAY) TO current-status
MOVE Shipped(ship-date: TODAY, tracking: "TRK12345") TO current-status
```
A field-less case is constructed by its bare name (`Pending`); the empty-parens form
(`Pending()`) is also accepted. Cases that declare fields require the argument list.

`VARIANT` types compile to a sealed abstract JVM class with one concrete inner class
per case. The TypeChecker enforces that every `MATCH` on a `VARIANT` handles all cases.

---

## 22. `MATCH` / Pattern Matching

```
match-stmt ::= MATCH expr ':'
                 (WHEN pattern ':'
                   statement+)+          -- arm body is indented on the following line(s)
                 (OTHERWISE ':'
                   statement+)?
               END-MATCH
```

Each arm uses block form: `WHEN pattern:` on its own line, with the body indented on the
following line(s) — the same `:`-plus-indent shape as `IF` (§8.6) and `FOR`. An inline body
on the same line as `WHEN … :` is **not** accepted.

### 22.1 Literal Patterns

```kobol
MATCH tx.type:
  WHEN "CREDIT":
    DO ApplyCredit USING tx
  WHEN "DEBIT":
    DO ApplyDebit USING tx
  WHEN "TRANSFER":
    DO ProcessTransfer USING tx
  OTHERWISE:
    RAISE ApplicationError "Unknown type: {tx.type}"
END-MATCH
```

### 22.2 Range Patterns

```kobol
MATCH inv.amount:
  WHEN 0.01 .. 999.99:
    MOVE "SMALL" TO size-category
  WHEN 1000 .. 9999.99:
    MOVE "MEDIUM" TO size-category
  WHEN 10000 ..:
    MOVE "LARGE" TO size-category
  OTHERWISE:
    MOVE "INVALID" TO size-category
END-MATCH
```

### 22.3 `VARIANT` Deconstruction Patterns

```kobol
MATCH status:
  WHEN Pending:
    DISPLAY "Awaiting confirmation"
  WHEN Active WITH order-date:
    DISPLAY "Active since {order-date}"
  WHEN Shipped WITH tracking:
    DISPLAY "Tracking: {tracking}"
  WHEN Closed WITH reason:
    DISPLAY "Closed: {reason}"
END-MATCH
```

### 22.4 Guard Clauses

```kobol
MATCH inv:
  WHEN Invoice WITH amount > 10000 AND NOT paid:
    DO EscalateToReview USING inv
  WHEN Invoice WITH NOT paid:
    DO SendReminder USING inv
  OTHERWISE:
    CONTINUE
END-MATCH
```

### 22.5 Exhaustiveness

The TypeChecker verifies that `MATCH` on a `VARIANT` type covers all cases.
A missing case is a **compile-time error** unless `OTHERWISE` is present.
`MATCH` on a scalar type (`TEXT`, `INTEGER`) does not require exhaustiveness but
warns if `OTHERWISE` is absent.

---

## 23. Structured Logging

The `LOG` statement emits structured, levelled output backed by SLF4J.

```
log-stmt ::= LOG level (string-literal | interpolated-string)
               (WITH (identifier ':' expr)*)?
```

Log levels: `TRACE` < `DEBUG` < `INFO` < `WARN` < `ERROR`

### 23.1 Simple Form

```kobol
LOG TRACE "Entering ProcessInvoice for {inv.id}"
LOG DEBUG "Tax: {inv.amount} * {rate} / 100 = {tax}"
LOG INFO  "Invoice {inv.id} processed in {elapsed}ms"
LOG WARN  "Invoice {inv.id} amount exceeds threshold: {inv.amount}"
LOG ERROR "Failed to process {inv.id}: {e.message}"
```

### 23.2 Structured Key-Value Form

For log aggregators (Splunk, Datadog, ELK) that parse JSON-lines output:

```kobol
LOG INFO "invoice.processed" WITH
  invoice-id : inv.id
  customer   : inv.customer
  amount     : inv.amount
  duration-ms: elapsed
```

Emits a JSON-structured log record with the event name as the `message` field and
the key-value pairs as additional fields.

### 23.3 `TEXT SENSITIVE` Interaction

`TEXT SENSITIVE` fields are always redacted in log output regardless of level:

```kobol
-- Given: card-number : TEXT(16) SENSITIVE
LOG INFO "Processing card {card-number}"
-- Emits: Processing card ***REDACTED***
```

---

## 24. Built-In Testing

`TEST` blocks are first-class language constructs. They are compiled only when
the `--test` flag is passed to the compiler.

### 24.1 Basic `TEST` Block

```
test-block ::= TEST string-literal ':'
                 (GIVEN ':' statement*)?
                 (WHEN  ':' statement*)+
                 (THEN  ':' (ASSERT expr)*)+
               END-TEST
```

```kobol
TEST "ApplyDiscount reduces invoice amount":
  GIVEN:
    LET inv  = Invoice(id: "INV001", amount: 100.00, paid: FALSE)
    LET rate = 10
  WHEN:
    CALL ApplyDiscount USING inv, rate GIVING result
  THEN:
    ASSERT result.amount = 90.00
    ASSERT result.amount < inv.amount
END-TEST
```

### 24.2 Table-Driven Tests

```kobol
TEST TABLE "CalculateTax is correct for all rates":
  COLUMNS: amount, rate, expected
  ROW:  100.00,  5.0,   5.00
  ROW:  200.00,  8.5,  17.00
  ROW: 1000.00, 20.0, 200.00
  WHEN: CALL CalculateTax USING amount, rate GIVING actual
  THEN: ASSERT actual = expected
END-TEST
```

### 24.3 Mock Procedures

```kobol
TEST "ProcessOrder uses stubbed exchange rate":
  MOCK FetchExchangeRate USING "EUR" RETURNS 1.23
  GIVEN:
    LET order = Order(amount: 100.00, currency: "EUR")
  WHEN:
    CALL ProcessOrder USING order GIVING result
  THEN:
    ASSERT result.converted-amount = 123.00
END-TEST
```

### 24.4 Test Runner

```bash
# Discover and run all TEST blocks:
java -jar kobolc.jar --test MyProgram.kbl

# Output:
# KOBOL TEST RUNNER
#   MyProgram.kbl  5/5 PASS
#   -------
#   5/5 PASS  (0.12s)
```

Output is JUnit-XML-compatible (`TEST-*.xml`) for integration with CI pipelines.

### 24.5 Assertion Reference

| Assertion | Description |
|-----------|-------------|
| `ASSERT expr = expected` | Equality |
| `ASSERT expr > / >= / < / <= expected` | Comparison |
| `ASSERT expr IS TRUE / FALSE` | Boolean |
| `ASSERT expr IS EMPTY / NOT EMPTY` | Collection / text |
| `ASSERT expr CONTAINS value` | Collection membership |
| `ASSERT RAISES ExceptionType: stmt` | Verifies exception is raised |

---

## 25. HTTP / REST Client

```kobol
PROCEDURE FetchRate USING currency : TEXT RETURNING DECIMAL:
  CALL http.GET
    USING   "https://api.example.com/rates/{currency}"
    HEADERS "Authorization: Bearer {API-KEY}",
            "Accept: application/json"
    TIMEOUT 30
    GIVING  response
  IF response.status = 200:
    RETURN response.body.rate AS DECIMAL
  ELSE:
    RAISE ApplicationError "Rate fetch failed: HTTP {response.status}"
  END-IF
END-PROCEDURE

-- POST with JSON body:
CALL http.POST
  USING  "https://api.example.com/payments"
  BODY   JSON payment-request
  GIVING response
```

Backed by Java's `java.net.http.HttpClient` (standard since Java 11).
TLS is always on for `https://` URLs; plain `http://` URLs trigger a compiler warning.

---

## 26. JSON / XML Serialisation

```kobol
-- Display JSON to stdout:
DISPLAY JSON invoice
DISPLAY JSON invoice-list PRETTY

-- Write JSON to file:
WRITE JSON invoice      TO "output/{invoice.id}.json"
WRITE JSON invoice-list TO "output/invoices.json" PRETTY

-- Parse JSON from text:
PARSE JSON json-text INTO invoice AS Invoice
```

`TEXT SENSITIVE` fields are excluded from JSON output by default.
Include them explicitly with `INCLUDING SENSITIVE`.

---

## 27. CLI & TUI Support

### 27.1 `ACCEPT` — Terminal Input

```
accept-stmt ::= ACCEPT identifier FROM (TERMINAL PROMPT literal
                                       | ARGUMENT literal (DEFAULT literal)?)
```

```kobol
-- Read from terminal with a prompt:
ACCEPT customer-id  FROM TERMINAL PROMPT "Enter customer ID: "
ACCEPT start-date   FROM TERMINAL PROMPT "Start date (YYYY-MM-DD): "

-- Read from command-line argument:
ACCEPT start-date   FROM ARGUMENT "--date"
ACCEPT batch-size   FROM ARGUMENT "--batch-size" DEFAULT 500

-- Yes/No confirmation (returns BOOLEAN):
IF NOT CONFIRM "Archive {record-count} records permanently?":
  DISPLAY "Aborted."
  STOP RUN
END-IF
```

`ACCEPT FROM TERMINAL` is backed by **JLine3** — provides line editing, history
(up/down arrow), and tab-completion where the TypeChecker can supply candidates.

---

### 27.2 `DISPLAY PROGRESS`

```kobol
DISPLAY PROGRESS processed OF total MESSAGE "Processing invoices"
```

Renders a live progress bar to stderr: `Processing invoices  [=====>    ] 52/100 (52%)`.
Updates in-place; does not scroll the terminal. Automatically hidden when stderr is not
a TTY (i.e., in CI pipelines, progress is suppressed).

---

### 27.3 `DISPLAY STYLED` (Coloured Output)

```kobol
DISPLAY STYLED "=== Invoice Report ===" BOLD UNDERLINE
DISPLAY STYLED "Passed: {passed}"       COLOR GREEN
DISPLAY STYLED "Failed: {failed}"       COLOR RED
DISPLAY STYLED "Warning: {warnings}"    COLOR YELLOW
```

Supported modifiers: `BOLD`, `ITALIC`, `UNDERLINE`, `COLOR name` (BLACK, RED, GREEN,
YELLOW, BLUE, MAGENTA, CYAN, WHITE, BRIGHT_*). Falls back to plain text when the
terminal reports no ANSI support.

---

## 28. REST API Server

**Kobol** programs can expose business logic as a lightweight REST API using the `SERVER`
block. The same `.kbl` file runs in batch mode (`--batch`) or server mode (`--server`).

### 28.1 `SERVER` Block

```
server-decl ::= SERVER AT PORT expr ':'
                  endpoint-decl*
                END-SERVER
```

```kobol
CONFIG:
  server-port : INTEGER FROM ENV "PORT" DEFAULT 8080

SERVER AT PORT server-port:

  ENDPOINT GET "/health":
    RESPOND WITH JSON MAP("status": "ok", "version": VERSION)
  END-ENDPOINT

  ENDPOINT GET "/invoices":
    CALL jdbc.query
      USING "SELECT * FROM invoices WHERE status = 'UNPAID'"
      INTO  results AS LIST OF Invoice
    RESPOND WITH JSON results
  END-ENDPOINT

  ENDPOINT GET "/invoices/{id}":
    ACCEPT id FROM PATH
    VALIDATE id MUST BE POSITIVE-INTEGER
    CALL jdbc.query
      USING "SELECT * FROM invoices WHERE id = ?"
      PARAMS id
      INTO  results AS LIST OF Invoice
    IF results IS EMPTY:
      RESPOND WITH STATUS 404 JSON MAP("error": "Invoice not found")
    ELSE:
      RESPOND WITH JSON results[0]
    END-IF
  END-ENDPOINT

  ENDPOINT POST "/invoices/{id}/process":
    ACCEPT id   FROM PATH
    ACCEPT body FROM REQUEST AS Invoice
    VALIDATE body.amount MUST BE > 0
    DO ProcessInvoice USING body
    RESPOND WITH STATUS 202 JSON MAP("status": "accepted", "id": id)
  END-ENDPOINT

END-SERVER
```

### 28.2 `ENDPOINT` Syntax

```
endpoint-decl ::= ENDPOINT method path ':'
                    statement*
                  END-ENDPOINT

method  ::= GET | POST | PUT | PATCH | DELETE
path    ::= string-literal  (may contain {param} placeholders)
```

### 28.3 Input Sources

| Source | Syntax | Description |
|--------|--------|-------------|
| Path parameter | `ACCEPT id FROM PATH` | `{id}` placeholder in endpoint path |
| Query string | `ACCEPT filter FROM QUERY "status"` | `?status=UNPAID` |
| Request body | `ACCEPT body FROM REQUEST AS TypeName` | JSON-decoded into a **Kobol** record |
| Request header | `ACCEPT token FROM HEADER "Authorization"` | Raw header value |

### 28.4 `RESPOND WITH`

```kobol
RESPOND WITH JSON value                        -- 200 OK, Content-Type: application/json
RESPOND WITH STATUS 201 JSON value             -- 201 Created
RESPOND WITH STATUS 404 JSON MAP("error": msg) -- 404 with JSON error body
RESPOND WITH STATUS 204                        -- No Content
RESPOND WITH TEXT "plain message"              -- 200 OK, Content-Type: text/plain
```

### 28.5 Security in API Endpoints

All `VALIDATE` constraints work inside `ENDPOINT` blocks. `TEXT SENSITIVE` fields are
automatically excluded from JSON responses unless `INCLUDING SENSITIVE` is specified.
HTTPS is enforced for production (server emits a startup warning if `PORT` is used
without a configured TLS certificate).

### 28.6 Backed by Javalin

The `SERVER` block compiles to a **Javalin** application (`io.javalin:javalin:6.x`).
Javalin is lightweight (~1 MB), needs no annotations, and embeds Jetty. The generated
`main()` configures routes, starts the server, and blocks until shutdown signal.

```bash
# Run as API server:
java -jar myapp.jar --server

# Run as batch (default):
java -jar myapp.jar --batch
java -jar myapp.jar          # --batch is the default
```

---

## 29. Project Model & Toolchain

Three user-facing build options exist, each serving a different audience.
They are not competing — they detect each other and coexist:

| Option | Audience |
|--------|----------|
| `kobol.toml` + `kobol build` | Standalone **Kobol** projects; no Gradle/Maven knowledge needed |
| `kobol-gradle-plugin` | Enterprise teams already on Gradle; mixed Kotlin+**Kobol** projects |
| `kobol-maven-plugin` | Maven shops |

`kobol build` auto-detects: `kobol.toml` → simple path; `build.gradle.kts` → delegate to Gradle; neither → single-file mode.

Note: the GraalVM Native Image Gradle plugin (`org.graalvm.buildtools:native-gradle-plugin`) is used **internally by the **Kobol** team** to compile the **Kobol** compiler itself into a native binary. It operates at the compiler-build level and is invisible to **Kobol** program authors.

---

### 29.1 `kobol.toml` — Project Descriptor

Every standalone **Kobol** project has a `kobol.toml` at its root.

```toml
[project]
name        = "invoice-processor"
version     = "1.0.0"
description = "Monthly invoice batch processor"
main        = "Main"          # PROCEDURE that serves as the entry point

[dependencies]
# Maven coordinates — resolved transitively from Maven Central
"org.postgresql:postgresql"                   = "42.7.3"
"com.fasterxml.jackson.core:jackson-databind" = "2.17.0"

[repositories]
# Additional Maven repositories (Maven Central is always included)
"company-nexus" = "https://nexus.example.com/repository/maven-releases"

[build]
source-dir  = "src/main"   # all .kbl files here are compiled
test-dir    = "src/test"   # TEST blocks compiled with --test
output-dir  = "build"
java-target = "21"         # JVM bytecode target
fat-jar     = true         # produce build/libs/<name>-<version>.jar

[server]                   # only when a SERVER block is present in source
port = 8080
```

---

### 29.2 Project Directory Layout

```
invoice-processor/
├── kobol.toml               ← project descriptor
├── kobol.lock               ← pinned dep versions (auto-generated, commit to VCS)
├── src/
│   ├── main/
│   │   ├── Main.kbl
│   │   ├── billing/
│   │   │   ├── Invoice.kbl
│   │   │   └── TaxCalculator.kbl
│   │   └── reporting/
│   │       └── ReportGenerator.kbl
│   └── test/
│       └── billing/
│           └── TaxCalculatorTest.kbl
└── build/
    ├── classes/             ← compiled .class files
    ├── libs/
    │   └── invoice-processor-1.0.0.jar
    └── test-results/        ← JUnit-compatible XML
```

Source files in sub-directories are automatically namespaced by directory name.
`billing/TaxCalculator.kbl` is in the `billing` module. An explicit `MODULE` declaration
(§14) formalises this; directory layout is the implicit equivalent.

---

### 29.3 `kobol` CLI Reference

**Single-file mode** (no `kobol.toml` required):

```bash
kobol hello.kbl              # compile + run
kobol --check hello.kbl      # type-check only, no code gen
kobol --watch hello.kbl      # recompile on save
kobol --repl                 # interactive REPL
```

**Project mode** (requires `kobol.toml` in current directory or a parent):

```bash
kobol new <name>             # scaffold a new project
kobol new <name> --template batch   # batch processing template
kobol new <name> --template api     # REST API server template

kobol build                  # compile src/main/**/*.kbl → build/
kobol run                    # build + run the main entry point
kobol run --main OtherEntry  # build + run a specific PROCEDURE
kobol run --server           # build + start the SERVER block
kobol run --batch            # explicit batch mode (default)
kobol test                   # build + run all TEST blocks
kobol test --filter "invoice*"
kobol check                  # type-check only
kobol clean                  # delete build/
kobol deps                   # print resolved dependency tree
kobol add "group:artifact:version"   # add dep to kobol.toml + update kobol.lock
kobol deps --update          # update all deps to latest compatible
```

---

### 29.4 Shebang Support

On Unix and macOS, a `.kbl` file can be made directly executable:

```kobol
#!/usr/bin/env kobol
-- daily-report.kbl
DISPLAY "Daily report for {TODAY}"
DO GenerateReport
```

```bash
chmod +x daily-report.kbl
./daily-report.kbl
```

---

### 29.5 Native Launcher Distribution

The `kobol` binary is produced by **GraalVM Native Image**:
- Startup: < 50 ms (no JVM warm-up)
- No JDK required to run the **compiler** — only to run the compiled programs
- Available via Homebrew, SDKMAN, Winget, GitHub Releases, and Docker

```bash
# macOS / Linux via Homebrew
brew install kobol-lang/tap/kobol

# Any platform via SDKMAN
sdk install kobol

# Windows via Winget
winget install kobol-lang.kobol

# Docker (no install)
docker run --rm -v "$PWD":/app kobol/kobol run Main.kbl
```

The VS Code extension bundles the native binary — installing the extension provides
a complete developer environment with zero separate installs.

The fat-jar (`kobolc.jar`) remains available for CI environments without GraalVM:
```bash
java -jar kobolc.jar hello.kbl          # single-file
java -jar kobolc.jar build              # project mode
```

---

### 29.6 Dependency Resolution

Maven coordinates in `kobol.toml` are resolved using the Gradle dependency resolution
engine (already used to build the compiler itself):

- Maven Central is always included
- Transitive dependencies are resolved and placed on the compile/run classpath
- Conflict resolution: highest-compatible version (standard Maven semantics)
- `kobol.lock` pins exact artifact SHAs — `kobol build` is reproducible without
  network access once the lock file exists
- `kobol add` appends to `kobol.toml` and regenerates `kobol.lock`

---

### 29.7 `kobol-gradle-plugin`

For enterprise teams already using Gradle (the majority of new JVM projects), the
`kobol-gradle-plugin` integrates **Kobol** compilation into an existing Gradle build
without requiring `kobol.toml`.

```kotlin
// build.gradle.kts
plugins {
    id("dev.kobol") version "1.0.0"
}

kobol {
    sourceSets {
        main { srcDir("src/main") }
        test { srcDir("src/test") }
    }
    main    = "Main"
    fatJar  = true
}

dependencies {
    // Uses Gradle's standard dependency configuration
    kobolImpl("org.postgresql:postgresql:42.7.3")
    kobolImpl("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}
```

Gradle tasks added by the plugin:

| Task | Description |
|------|-------------|
| `kobolBuild` | Compile all `src/main/**/*.kbl` |
| `kobolRun` | Build + run the entry-point PROCEDURE |
| `kobolTest` | Build + run all TEST blocks |
| `kobolCheck` | Type-check only |
| `kobolJar` | Produce a fat-jar |

This enables mixed Kotlin+**Kobol** projects in the same build, sharing Gradle's
dependency graph, Nexus/Artifactory configuration, and CI pipeline.

Published to the **Gradle Plugin Portal** under the ID `dev.kobol`.

---

## 30. XML Processing

**Kobol** provides first-class XML support for the domains where XML is the standard
interchange format: financial messaging (ISO 20022 / SWIFT MX), healthcare (HL7 FHIR,
CDA), government data exchange (NIEM, GovTalk), and EDI. XML support is aligned with
COBOL 2023's `XML GENERATE` / `XML PARSE` statements.

### 30.1 `DISPLAY XML` — Serialisation

```kobol
-- Output XML to stdout:
DISPLAY XML invoice
DISPLAY XML invoice PRETTY

-- Write XML to file:
WRITE XML invoice      TO "output/invoice-{invoice.id}.xml"
WRITE XML invoice-list TO "output/invoices.xml" PRETTY

-- Optional root element override:
WRITE XML invoice TO "output/inv.xml" ROOT "InvoiceDocument"
```

Field names are converted to `camelCase` XML element names by default.
A `TEXT SENSITIVE` field is excluded unless `INCLUDING SENSITIVE` is specified.

### 30.2 `PARSE XML` — Deserialisation

```kobol
-- Parse XML text into a typed record:
PARSE XML xml-text INTO invoice AS Invoice

-- Parse XML from a file:
PARSE XML FILE "data/invoice.xml" INTO invoice AS Invoice

-- Parse a list (repeating elements under a wrapper element):
PARSE XML FILE "data/invoices.xml" INTO invoice-list AS LIST OF Invoice ROOT "Invoices"
```

Unmapped XML elements are silently ignored. A missing required field (one with no
default) raises `KobolConversionError` with the XPath of the missing element.

### 30.3 XML Namespace Handling

```kobol
-- Declare prefix-to-namespace mappings in CONFIG:
CONFIG:
  swift-ns : TEXT = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"

-- Reference in PARSE / DISPLAY:
PARSE XML swift-message INTO payment-request AS PaymentInstruction
  NAMESPACES "pain" : swift-ns
```

Namespaces are validated at parse time; a document whose root namespace does not
match the declared mapping raises a descriptive error rather than silently
producing empty fields.

### 30.4 Error Handling

```kobol
TRY:
  PARSE XML FILE "data/input.xml" INTO record AS MyRecord
ON CONVERSION-ERROR AS e:
  LOG ERROR "XML parse failed: {e.message}"
  RAISE ApplicationError "Invalid input document"
END-TRY
```

`CONVERSION-ERROR` covers both malformed XML and schema-mismatch conditions.

### 30.5 JVM Backing

Backed by **Jackson Dataformat XML** (`com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.x`),
which already appears in the `kobol.toml` dependency ecosystem. The same
`TEXT SENSITIVE` codec used for JSON serialisation is reused, ensuring consistent
redaction behaviour across both formats.

```bash
# kobol.toml dependency (auto-added when XML features are used):
"com.fasterxml.jackson.dataformat:jackson-dataformat-xml" = "2.17.0"
```
