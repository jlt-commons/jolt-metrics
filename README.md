# jolt-metrics

A code metrics library for Clojure/Jolt and Scheme source, written in Jolt. It computes
cyclomatic complexity, cognitive complexity, Halstead metrics, the
maintainability index, and architecture coupling. Its report leads with a ranked
refactoring queue, so an agent or a human can see what to clean up first and why.

Every metric's computation is checked against a
[writ](https://github.com/jlt-commons/writ) spec, so the numbers are held to a
stated contract, not just unit tests.

## Metrics

| Metric | Source | What it measures |
|---|---|---|
| **LOC / SLOC / CLOC** | `metrics.loc` | total, source, and comment lines |
| **Cyclomatic complexity** | `metrics.cyclomatic` | independent paths |
| **Cognitive complexity** | `metrics.cognitive` | how hard the control flow is to read |
| **Halstead metrics** | `metrics.halstead` | vocabulary, length, volume, difficulty, effort, time, bugs |
| **Maintainability index** | `metrics.maintainability` | composite: Halstead volume + cyclomatic + LOC |
| **Nesting depth** | `metrics.cognitive` | how deeply control structures nest |
| **Coupling / instability** | `metrics.coupling` | afferent/efferent coupling and instability |
| **Hotspot risk** | `metrics.hotspot` | how complex a file is and how often it changes |

## Usage

### CLI

```console
jolt -m metrics.cli src                # print the Markdown report
jolt -m metrics.cli src report.md      # write it to a file
jolt -m metrics.cli src --format edn   # every finding, as data
jolt -m metrics.cli --help
```

| Option | Default | Meaning |
|---|---|---|
| `--format md\|edn` | `md` | Markdown for reading, EDN with every finding |
| `--top N` | 25 | queue entries to show in Markdown |
| `--days N` | 90 | how much git history counts as recent churn |
| `--no-git` | | skip git history, so no churn and no hotspots |
| `--exclude GLOB` | | leave out matching files, relative to the directory; can be repeated |
| `--config FILE` | `<dir>/.jolt-metrics.edn` | the config file |

The command exits 2 on bad usage or a missing directory. A file that can't be read
doesn't fail the run; the report lists it under `Files not analyzed`. Reading never
evaluates the source: `#=` is a read error.

### Configuration

A project can list what to leave out once, in `.jolt-metrics.edn` in the analyzed
directory:

```clojure
{:exclude ["vendor/**" "target/**" "src/parser_generated.clj"]
 :test-paths ["test" "bench"]
 :api-namespaces ["my.lib" "my.lib.api.*"]
 :api-paths ["src/my/lib/core"]}
```

Patterns are relative to that directory, and `--exclude` adds to the list. `*` stays
within a directory, `**` crosses them, and a bare directory covers its contents.
Nothing is excluded by default, so vendored and generated code counts until you say
otherwise. Files under `:test-paths` (by default `test` and `tests`, as a directory
at any depth, or a glob) are test code: they're measured, but not queued or called
unused.

`:api-namespaces` names the namespaces that are public API, called from code outside
the analyzed files, so their public functions are never listed as unreferenced. An
entry is a namespace name, or a glob where `*` stands for any run of characters,
dots included: `"my.lib.*"` covers `my.lib.core` and `my.lib.core.impl` but not
`my.lib` itself. `:api-paths` does the same for files by path, with globs like
`:exclude`'s, which covers files that have no `ns` form to match. Private functions
in either are still checked.

### The report

1. **Summary**: finding counts and project totals.
2. **Files not analyzed**: anything unreadable, with the reason.
3. **Refactoring queue**: functions and files over a threshold, worst first. Each
   entry has its location and line range, its numbers with the broken ones in bold,
   and what to do about it. Tests aren't queued.
4. **Hotspots**: complex files that keep changing. Their contents rank higher in
   the queue.
5. **Cleanup candidates**: unused requires, and functions nothing mentions. Matching
   is by name and conservative, but runtime lookups and outside callers can't be
   seen, so check public functions before removing them, and name your API
   namespaces in the config to keep them off the list.
6. **Files** and **Architecture**: the numbers per file, coupling, and dependencies.
7. **Thresholds**: what the severities mean.

| Rule | Warning | Critical | Where the limits come from |
|---|---|---|---|
| Cyclomatic complexity | > 10 | > 20 | [NIST SP 500-235](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication500-235.pdf) and [PMD](https://docs.pmd-code.org/latest/pmd_rules_java_design.html) use 10; [ESLint](https://eslint.org/docs/latest/rules/complexity) uses 20 |
| Cognitive complexity | > 15 | > 25 | [Sonar's default](https://community.sonarsource.com/t/s3776-reason-for-the-current-default-value-of-15/127103) is 15, and 25 for C-family languages; PMD uses 15 |
| Nesting depth | > 3 | > 5 | PMD flags 3 nested `if`s; [ESLint](https://eslint.org/docs/latest/rules/max-depth) uses 4 |
| Function source lines | > 20 | > 50 | the [Clojure style guide](https://guide.clojure.style/) says 10; [ESLint](https://eslint.org/docs/latest/rules/max-lines-per-function) uses 50 |
| Function parameters | > 4 | > 7 | the Clojure style guide says three or four; [Checkstyle](https://checkstyle.org/checks/sizes/parameternumber.html) uses 7 |
| Maintainability index (comment-free) | <= 38 | < 10 | the original limit of 65, rescaled by 100/171; [Visual Studio](https://learn.microsoft.com/en-us/visualstudio/code-quality/code-metrics-maintainability-index-range-and-meaning) turns red below 10 |
| File source lines | > 500 | > 1000 | [metripy](https://github.com/zimmer-yan/metripy); [ESLint](https://eslint.org/docs/latest/rules/max-lines) uses 300 and [Checkstyle](https://checkstyle.org/checks/sizes/filelength.html) 2000 |
| File dependencies | > 19 | > 39 | [Checkstyle](https://checkstyle.org/checks/metrics/classfanoutcomplexity.html) and PMD use 20 for classes; ast-metrics uses 20 and 40 |
| Hotspot risk (at least 3 changes) | >= 0.5 | >= 0.8 | no published standard; these are our choice |

Function length sits between the Clojure style guide and general-purpose tools.
Taken literally, the guide's 10 lines flags a quarter of the functions in the
Clojure projects we measured, while 20 flags about one in ten. These limits are
rules of thumb rather than research results; even NIST calls the exact number
"somewhat controversial".

### API

```clojure
(require '[metrics.core :as core]
         '[metrics.git :as git]
         '[metrics.report :as report])

;; one file
(core/analyze-source "src/foo.clj" (slurp "src/foo.clj"))

;; several files, with coupling resolved between them
(def result (core/analyze-files [["src/a.clj" (slurp "src/a.clj")]
                                 ["src/b.clj" (slurp "src/b.clj")]]))

;; a whole directory
(core/analyze-dir "src")

;; churn makes hotspots
(core/analyze-dir "src" {:changes (git/changes "src" 90) :days 90})

;; the Markdown report
(report/render result)
```

A result has `:files`, `:errors`, `:edges`, `:summary`, `:queue`, `:hotspots` and
`:cleanup`. Each file carries `:loc`, `:cyclomatic`, `:cognitive`, `:halstead`,
`:maintainability-index-without-comments`, `:functions` (per-definition metrics),
and the coupling fields `:efferent-coupling`, `:afferent-coupling`, `:instability`,
`:instability-band`.

## Architecture

The pipeline keeps the metric math pure and swappable over different parsers:

```
source ──► normalized model ──► pure metric visitors ──► queue ──► report
  (reader, scanner)             halstead, mi, loc,       (queue,   (report)
                                cyclomatic, cognitive,   cleanup)
                                coupling, hotspot
```

- **`metrics.reader`** parses Clojure/Jolt forms into a normalized model: each
  definition's decision kinds, cognitive flow tree, Halstead operators/operands,
  logical LOC, and the file's imports. Every metric walks forms through one
  function, so they all see the same code: collections are code, while quoted data,
  `comment` forms and `case` constants aren't. Syntax-quote is read back, so a macro
  is measured as written.
- **`metrics.scanner`** lexes the raw text for what the reader discards: docstrings,
  discards, and where each list ends.
- **`metrics.*`** metric namespaces are pure functions over that model. They
  never read files and never mention writ.
- **`metrics.core`** orchestrates: parse, compute per-definition and per-file
  metrics, resolve coupling across files, and roll the project up.
- **`metrics.queue`** and **`metrics.cleanup`** turn the numbers into work.
- **`metrics.git`** reads churn. It is the only part that shells out.
- **`metrics.report`** renders Markdown.

A definition is a `defn`, `defn-`, `defmacro`, `defmethod`, `deftest`, a method of
`defrecord`, `deftype`, `extend-type` or `extend-protocol`, or a `def`, `defonce` or
`defmulti` of an `fn`. Other top-level code counts toward its file's complexity.

### Languages

The pipeline only sees a language adapter (`metrics.language`), which says how to
parse and scan a language. Parsing has to end in the forms the shared walker
understands, so every language gets the same metrics from the same code. Adding a
language means writing an adapter and listing it there.

## Scheme

`.ss`, `.scm`, `.sls` and `.sps` files are analyzed as Scheme, targeting Chez and
Gambit; other dialects work as far as they share that syntax. A directory with both
languages gets one report. All of it lives under `metrics.scheme`:

- **`metrics.scheme.scanner`** says what a Scheme token is: a comma is unquote, a
  bracket is a paren, and `#` covers block and datum comments and Gambit's `#!void`.
- **`metrics.scheme.reader`** parses those tokens into forms. It only parses, so
  nothing in the source can run.
- **`metrics.scheme.normalize`** rewrites Scheme into the walker's shapes, and is the
  one place that decides what Scheme means.
- **`metrics.scheme.deps`** finds what a file depends on.

| Scheme | Measured as |
|---|---|
| `(define (f x) ...)`, `(define f (lambda ...))` | a function |
| `(define f (let (...) (lambda ...)))` | a function, its closure nested |
| `define-syntax`, `define-macro` | a macro, with only its templates measured |
| a `define` of a function inside a function, a `letrec`/`letrec*` lambda | a helper of its own, as by `letfn` |
| `lambda`, `case-lambda` | a closure, which nests |
| `(let name (...) ...)`, `do` | a loop |
| `cond`, `exclusive-cond` | an else-if chain; `else` and `#t` are the free default |
| `case`, `syntax-rules`, `syntax-case` | a switch; constants and patterns aren't code |
| `unless` | `when-not` |
| `guard` | `try` and `catch`, its clauses a `cond` inside the catch |
| `begin`, `module`, `library`, `eval-when` at top level | opened, so what's inside counts |
| quoted data | not code; in a quasiquote, only what's unquoted is |

Every other list is a call. A name that would read as a Clojure form, like the `loop`
in `(let loop ...)`, stays a call, and a form too malformed to translate, as can
happen in a macro template, is read as one.

Files depend on each other through `load`, `include` and `##include`, and through
`import` of a `library`. A load's path can be relative to its file or the analyzed
root, or the unique file ending that way. One built at runtime can't be known, and an
ambiguous one makes no edge.

Public functions aren't reported as unreferenced in Scheme, because a runtime's names
are reached from emitted code and strings. There's no `private` to check, and no
unused imports yet. Top-level code like `(let () (define ...))` counts toward its
file but isn't queued.

### The scores

The index is `171 - 5.2*ln(V) - 0.23*G - 16.2*ln(lines)`, scaled to 0..100 and
clamped at 0. A definition uses its own source lines rather than a form count, which
would overstate nested expressions. A file uses the average of its definitions, as
the original formula does; summing them would make it a measure of file length. The
report ranks on this comment-free score. `:maintainability-index` adds the comment
term, can exceed 100, and is kept for information.

`#?` reader conditionals are read for the running platform, so another platform's
branch is not measured.

Each metric namespace has a writ spec in `test/metrics/*_spec.clj` that states
its contract (signatures, a state graph, a model law, and worked examples).
The `*_test.clj` files cover the rest with plain `clojure.test`, and
`walker_test.clj` pins what the reader counts with golden examples.

## Named helpers

A function's named inner helpers are measured on their own, not charged to it. Each
function a `letfn` binds is a unit named `outer/helper`, with its own complexity,
nesting, lines and parameters, and its kind is `:helper`. A helper's helpers are
`outer/helper/inner`. The outer function keeps its own code and lines and none of
theirs; an anonymous `fn` has no name to stand on its own under, so it stays part of
the function around it. In Scheme, a `define` of a function inside another and a
lambda bound by `letrec` or `letrec*` are helpers the same way. A lambda bound by a
plain `let` is not, and a named `let` is a loop. Helpers are never cleanup candidates.
A file's total complexity counts each helper as the function it is.

## The decision table

Cyclomatic complexity counts one decision point per:

- `if if-not if-let if-some when when-not when-let when-some when-first` — 1
- `cond` — 1 per clause; a trailing keyword or `true` test is the free default
- `condp` — 1 per clause; a lone trailing form is the free default
- `case` — 1 per case except the default
- `cond-> cond->>` — 1 per clause
- `and or` — 1 per operator, so n-1 for n arguments
- `for doseq` — 1 per binding, 1 per `:when` or `:while`
- `dotimes while loop` — 1
- `catch` — 1 (`try` itself is not a branch)

Cognitive complexity follows the [SonarSource
paper](https://www.sonarsource.com/docs/CognitiveComplexity.pdf):

- `if`/`when`/`condp`/`case`/loops/`catch` add 1 plus nesting depth
- `cond` is an if/else-if chain, so its first test costs like an `if` and each later
  one like an else-if; `case` and `condp` count as a `switch`
- `cond->` is a series of independent `if`s
- an else-if adds a flat 1, including an `if` directly inside an `else`
- a run of a logical operator adds a flat 1
- `try` and `finally` add nothing, nor do `some->` and friends, like the paper's
  null-coalescing operators
- `fn`/`reify`/`proxy` add nothing but nest what they hold

It differs from the paper in two ways. A plain `else` is free, since nearly every
Clojure `if` has one. Recursion adds nothing, though the paper adds 1 per function
in a cycle.

## Tests

```console
jolt -M:test:dev
```

Runs every writ spec and the reader behaviour tests. Specs are discovered by
directory scan; add a `*_spec.clj` file and it is picked up.

`self_test.clj` runs the report on `src` and fails if anything is queued, so a
change that crosses a threshold has to refactor or argue the threshold down.
