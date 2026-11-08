---
name: jolt-metrics
description: >-
  Use when working in the jolt-metrics library: a Jolt code-metrics library
  that computes LOC, cyclomatic and cognitive complexity, Halstead metrics,
  the maintainability index, and architecture coupling over Clojure/Jolt and
  Scheme source, and renders a report led by a ranked refactoring queue. Covers adding
  or changing a metric (write its writ spec first, then the pure implementation),
  the normalized model, the reader and scanner, and the coupling, queue and
  report pipeline.
---

# jolt-metrics

A code metrics library for Clojure/Jolt and Scheme source, written in Jolt. Every metric's
computation is checked against a writ spec, not just unit tests.

## Layering

```
source ──► normalized model ──► pure metric visitors ──► queue ──► report
  reader.clj, scanner.clj       halstead.clj             queue.clj  report.clj
                                maintainability.clj      cleanup.clj json.clj
                                cyclomatic.clj                      html.clj
                                cognitive.clj
                                coupling.clj
                                hotspot.clj, rules.clj
                                loc.clj (over scanner's line classes)
```

- **`metrics.reader`** is plain Clojure (not writ-checked): it reads forms with
  `clojure.core/read` and maps them to the normalized model. Every walker goes
  through `code-children`, so the metrics can't disagree about what is code.
- **`metrics.scanner`** is plain Clojure too: a lexer over the raw text, total on
  any input, for docstrings, discards and where each list ends. A syntax map says
  what the tokens are.
- **`metrics.*`** metric namespaces are pure and writ-constrained: single-arity
  fns, no interop, recursion only via `case`-destructured fields of a `data`
  type (or `rest`/`dec` under a test). They never mention writ and never do I/O.
- **`metrics.core`** orchestrates parse → per-file → coupling → queue → summary.
- **`metrics.paths`** handles globs and resolving loaded files; **`metrics.config`**
  reads `.jolt-metrics.edn`.
- **`metrics.queue`** holds the thresholds and ranks the findings; **`metrics.cleanup`**
  finds what looks unused; **`metrics.git`** reads churn and is the only shell-out.
- **`metrics.report`** renders Markdown; **`metrics.json`** the lean, versioned JSON
  for tools (snake_case keys, always present; bump `schema-version` when a key goes
  or changes meaning); **`metrics.html`** one self-contained page that inlines
  `resources/metrics/html/report.{css,js}` and embeds the JSON with `<` escaped.
  All text in `metrics.html` goes through its escaping `markup`; only `raw` skips
  it, so never wrap user data in `raw`. **`metrics.cli`** is the entry point.

**Languages stay separate.** The pipeline only sees an adapter from
`metrics.language`: `:parse` (text to forms in the walker's shapes, plus dependencies)
and `:scan`. Nothing outside `src/metrics/scheme*` may know Scheme exists, apart from
the one line listing its adapter. A Scheme fix belongs in `metrics.scheme.normalize`,
not in the walker: to count a Scheme form differently, change what it normalizes to.

- A raw head the walker would take for a Clojure form must stay in `clojure-heads`
  in `metrics.scheme.normalize`. Adding a form to the walker's tables means adding it
  there too, or Scheme calls of that name change meaning.
- `exprs` is eager on purpose: a malformed form has to fail inside the `expr` that
  can read it as a call instead.
- Pin Scheme with hand-scored numbers in `scheme_normalize_test.clj`.

Reading is safe by construction: `read-forms` binds `*read-eval*` to false and reads
in a scratch namespace. Keep it that way, since the source is untrusted data.

## Adding a metric (spec first)

1. Write the writ spec `test/metrics/<name>_spec.clj`:
   - `(spec metrics.<name> {:require :proved})`
   - a `data`/`refine` type and a `graph` (every spec needs one), `ann` per
     public fn, then laws — a model law (`(= (f x) (model x))`) plus worked
     examples and edge cases.
2. Write the pure implementation `src/metrics/<name>.clj`.
3. Run `jolt -M:test metrics.<name>-spec` until green (`-M:test:dev` uses a
   `../writ` checkout instead of the pinned writ).

writ rules that have cost time here:

- The implementation must have **no `map`-over-`(Vec T)` recursion**; the static
  checker rejects it ("does not descend"). Use a fixed-branching `data` type and
  recurse through `case`-destructured fields (see `metrics.cognitive`).
- Laws that use `log`/`sin`/`sqrt`/`distinct`/`frequencies`/division are only
  `:tested`; mark them `{:require :tested :because "..."}`.
- The gap check fails when a `forall` is missing for a fn — a handful of literal
  examples don't pin a function down.
- Every public fn in a writ-checked namespace needs an `ann`; a helper must be
  `defn-`.
- A law's model recomputes the formula, so it guards arithmetic, not what the reader
  feeds in. Pin reader behaviour with golden examples in `walker_test.clj`.

## The decision table (cyclomatic)

1 per: `if if-not if-let if-some when when-not when-let when-some when-first`;
1 per `cond` clause except a trailing keyword or `true`; 1 per `condp` clause except
a lone trailing form; 1 per non-default `case`; 1 per `cond->`/`cond->>` clause;
n-1 per `and`/`or` of n arguments; 1 per `for`/`doseq` binding, `:when` or `:while`;
1 per `dotimes while loop`; 1 per `catch` (`try` itself is not a branch). Keep this
table and `metrics.reader` in sync.

Cognitive follows the SonarSource paper, with a free plain `else` and no recursion
increment. `cond` is an else-if chain, `cond->` a series of ifs, and `case`/`condp`
are switches: see the `tree-builders` in `metrics.reader`.

## Commands

- `jolt -M:test` — run all writ specs + reader tests; `-M:test:dev` swaps in a
  sibling `../writ` checkout.
- `jolt -m metrics.cli <dir> [out] [--format md|edn|json|html] [--top N] [--days N]
  [--no-git] [--exclude GLOB] [--config FILE]` — the report; without `--format`, out's
  extension picks it.

Note: running `jolt -M:test` relies on `:main-opts` in `deps.edn`; do not
also pass `-m metrics.runner` on the CLI, or it leaks into the runner's args.

## Conventions

- Metric namespaces return plain maps; `metrics.core` aggregates them.
- The file-level Halstead is the average of its definitions', skipping empty ones.
  The file-level index is the original formula: the average volume, complexity and
  source lines of its definitions. A definition's index uses its own lines.
- The report ranks on the comment-free index; the comment term is informational.
- `maintainability-index` takes the comment weight rather than loc and cloc, so
  callers work it out with `comment-weight`.
- A threshold change belongs in `metrics.queue` (or `mi-band` in `metrics.rules`).
  Update the README's table too, including where the number comes from.
