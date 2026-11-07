---
name: jolt-metrics
description: >-
  Use when working in the jolt-metrics library: a Jolt code-metrics library
  that computes LOC, cyclomatic and cognitive complexity, Halstead metrics,
  the maintainability index, and architecture coupling over Clojure/Jolt
  source, and renders a Markdown report. Covers adding or changing a metric
  (write its writ spec first, then the pure implementation), the normalized
  model, the reader adapter, and the coupling/aggregation/report pipeline.
---

# jolt-metrics

A code metrics library for Clojure/Jolt source, written in Jolt. Every metric's
computation is checked against a writ spec, not just unit tests.

## Layering

```
source ──► normalized model ──► pure metric visitors ──► markdown report
   reader.clj   functions /          halstead.clj            report.clj
                decision-kinds /     maintainability.clj
                flow-tree /          cyclomatic.clj
                operators / imports  cognitive.clj
                                     coupling.clj
                loc.clj (line-based, works off the raw text)
```

- **`metrics.reader`** is plain Clojure (not writ-checked): it reads forms with
  `clojure.core/read` and maps them to the normalized model. It may use `map`,
  `reduce`, mutual recursion, `case` freely.
- **`metrics.*`** metric namespaces are pure and writ-constrained: single-arity
  fns, no interop, recursion only via `case`-destructured fields of a `data`
  type (or `rest`/`dec` under a test). They never mention writ and never do I/O.
- **`metrics.core`** orchestrates parse → per-file → coupling → summary.
- **`metrics.report`** renders Markdown; **`metrics.cli`** is the entry point.

## Adding a metric (spec first)

1. Write the writ spec `test/metrics/<name>_spec.clj`:
   - `(spec metrics.<name> {:require :proved})`
   - a `data`/`refine` type and a `graph` (every spec needs one), `ann` per
     public fn, then laws — a model law (`(= (f x) (model x))`) plus worked
     examples and edge cases.
2. Write the pure implementation `src/metrics/<name>.clj`.
3. Run `jolt -M:test:dev metrics.<name>-spec` until green.

writ rules that have cost time here:

- The implementation must have **no `map`-over-`(Vec T)` recursion**; the static
  checker rejects it ("does not descend"). Use a fixed-branching `data` type and
  recurse through `case`-destructured fields (see `metrics.cognitive`).
- Laws that use `log`/`sin`/`sqrt`/`distinct`/`frequencies`/division are only
  `:tested`; mark them `{:require :tested :because "..."}`.
- The gap check fails when a `forall` is missing for a fn — a handful of literal
  examples don't pin a function down.

## The decision table (cyclomatic)

1 per: `if if-not if-let if-some when when-not when-let when-some`;
1 per non-`:else`/`true` `cond`/`condp` clause; 1 per non-default `case`;
1 per `and`/`or`; 1 per `for doseq dotimes while loop`; 1 per `catch`
(`try` itself is not a branch). Keep this table and `metrics.reader` in sync.

## Commands

- `jolt -M:test:dev` — run all writ specs + reader tests.
- `jolt -m metrics.cli <dir> [out.md]` — the Markdown report.

Note: running `jolt -M:test:dev` relies on `:main-opts` in `deps.edn`; do not
also pass `-m metrics.runner` on the CLI, or it leaks into the runner's args.

## Conventions

- Metric namespaces return plain maps; `metrics.core` aggregates them.
- The file-level Halstead is the average of its functions'; the file-level
  maintainability index uses that average volume plus the file's cyclomatic,
  logical LOC, LOC and comment LOC.
