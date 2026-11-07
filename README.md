# jolt-metrics

A code metrics library for Clojure/Jolt source, written in Jolt. It computes
cyclomatic complexity, cognitive complexity, Halstead metrics, the
maintainability index, and architecture coupling, and renders the result as a
Markdown report an agent or a human can read.

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
| **Coupling / instability** | `metrics.coupling` | afferent/efferent coupling and instability |

## Usage

### CLI

```console
jolt -m metrics.cli src                # print the Markdown report
jolt -m metrics.cli src report.md      # write it to a file
```

The report has four sections: a summary, a per-file table, the top offenders
by cyclomatic and cognitive complexity, and the architecture (coupling and the
dependency edges between namespaces).

### API

```clojure
(require '[metrics.core :as core]
         '[metrics.report :as report])

;; one file
(core/analyze-source "src/foo.clj" (slurp "src/foo.clj"))

;; several files, with coupling resolved between them
(def result (core/analyze-files [["src/a.clj" (slurp "src/a.clj")]
                                 ["src/b.clj" (slurp "src/b.clj")]]))

;; a whole directory
(core/analyze-dir "src")

;; the Markdown report
(report/render result)
```

Each file's result carries `:loc`, `:cyclomatic`, `:cognitive`, `:halstead`,
`:maintainability-index`, `:functions` (per-function metrics), and the coupling
fields `:efferent-coupling`, `:afferent-coupling`, `:instability`.

## Architecture

The pipeline keeps the metric math pure and swappable over different parsers:

```
source ──► normalized model ──► pure metric visitors ──► markdown report
   (reader)   functions / decision  halstead, mi, loc,       (report)
              kinds / flow tree /   cyclomatic, cognitive,
              operators / imports   coupling
```

- **`metrics.reader`** parses Clojure/Jolt forms into a normalized model: each
  function's decision kinds (cyclomatic), cognitive flow tree, Halstead
  operators/operands, logical LOC, and the namespace imports.
- **`metrics.*`** metric namespaces are pure functions over that model. They
  never read files and never mention writ.
- **`metrics.core`** orchestrates: parse, compute per-function and per-file
  metrics, resolve coupling across files, and roll the project up.
- **`metrics.report`** renders Markdown.

Each metric namespace has a writ spec in `test/metrics/*_spec.clj` that states
its contract (signatures, a state graph, a model law, and worked examples).
`test/metrics/reader_test.clj` covers the reader with plain `clojure.test`.

## The decision table

Cyclomatic complexity counts one decision point per:

- `if if-not if-let if-some when when-not when-let when-some` — 1
- `cond condp` — 1 per clause whose test is not `:else` or `true`
- `case` — 1 per case except the default
- `and or` — 1 (logical operator)
- `for doseq dotimes while loop` — 1 (loop)
- `catch` — 1 (`try` itself is not a branch)

Cognitive complexity: `if`/`when`/`cond`/`case`/loops/`try`/`catch` add 1 plus
their nesting depth, and an `else if` sits at the same depth as its `if`.

## Tests

```console
jolt -M:test:dev
```

Runs every writ spec and the reader behaviour tests. Specs are discovered by
directory scan; add a `*_spec.clj` file and it is picked up.
