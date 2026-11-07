# jolt-metrics

A code metrics library for Clojure/Jolt source, written in Jolt. It computes
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

The command exits 2 on bad usage or a missing directory. A file that can't be read
doesn't fail the run; the report lists it under `Files not analyzed`. Reading never
evaluates the source: `#=` is a read error.

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
   seen, so check public functions before removing them.
6. **Files** and **Architecture**: the numbers per file, coupling, and dependencies.
7. **Thresholds**: what the severities mean.

| Rule | Warning | Critical |
|---|---|---|
| Cyclomatic complexity | > 10 | > 20 |
| Cognitive complexity | > 15 | > 25 |
| Nesting depth | > 3 | > 5 |
| Function source lines | > 30 | > 50 |
| Function parameters | > 5 | > 7 |
| Maintainability index (comment-free) | | <= 40 |
| File source lines | > 500 | > 1000 |
| File dependencies | > 19 | > 39 |
| Hotspot risk (at least 3 changes) | >= 0.5 | >= 0.8 |

The index's warning band isn't used: on Clojure it catches a third to a half of
ordinary functions, and those that matter break a more specific rule anyway.

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

Cognitive complexity follows the SonarSource rules, except that a plain `else` is
free, since nearly every Clojure `if` has one:

- `if`/`when`/`cond`/`condp`/`case`/`cond->`/loops/`catch` add 1 plus nesting depth
- an else-if adds a flat 1
- a run of a logical operator adds a flat 1
- `try` adds nothing
- `fn`/`letfn`/`reify`/`proxy` add nothing but nest what they hold

## Tests

```console
jolt -M:test:dev
```

Runs every writ spec and the reader behaviour tests. Specs are discovered by
directory scan; add a `*_spec.clj` file and it is picked up.

`self_test.clj` runs the report on `src` and fails if anything is queued, so a
change that crosses a threshold has to refactor or argue the threshold down.
