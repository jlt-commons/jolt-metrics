# jolt-metrics

A code metrics library for Clojure/Jolt and Scheme, written in Jolt. It computes
cyclomatic and cognitive complexity, Halstead metrics, the maintainability index
and coupling, and finds unusual commits, files whose vocabulary doesn't fit the
project, and near-copies of definitions. The report opens with a ranked
refactoring queue: what to clean up first, and why.

## Metrics

| Metric | Source | What it measures |
|---|---|---|
| LOC / SLOC / CLOC | `metrics.loc` | total, source, and comment lines |
| Cyclomatic complexity | `metrics.cyclomatic` | independent paths |
| Cognitive complexity | `metrics.cognitive` | how hard the control flow is to read |
| Halstead metrics | `metrics.halstead` | vocabulary, length, volume, difficulty, effort, time, bugs |
| Maintainability index | `metrics.maintainability` | composite: Halstead volume + cyclomatic + LOC |
| Nesting depth | `metrics.cognitive` | how deeply control structures nest |
| Coupling / instability | `metrics.coupling` | afferent/efferent coupling and instability |
| Fan-in / fan-out / reach | `metrics.calls` | per function: how many functions refer to it, how many it refers to, and how many depend on it at any distance (Clojure only) |
| Hotspot risk | `metrics.hotspot` | how complex a file is and how often it changes |
| Unusual changes | `metrics.entropy` | commits that changed the code's information content far more than usual |
| Entropy / surprisal | `metrics.entropy` | a file's word entropy, and how unexpected its words are given the rest of the project |
| Compressed size / redundancy | `metrics.compression` | an upper bound on a definition's or file's Kolmogorov complexity, and how much of a file is repetition |
| Near-duplicates | `metrics.compression` | pairs of definitions whose normalized compression distance says one is nearly a copy of the other |
| Lint findings | `metrics.lint` | idioms with a simpler form and likely bugs, after [clj-kondo](https://github.com/clj-kondo/clj-kondo)'s rules (Clojure only) |
| Abstraction opportunities | `metrics.abstraction` | code shapes that recur often enough for one function to replace them, with their parameters and what extracting them saves |

## Usage

Run the standalone `jolt-metrics` binary, or
`jolt -m metrics.cli`. Both take the same arguments. Churn, hotspots and unusual
changes need `git` on the PATH.

### The standalone binary

Each [release](https://github.com/jlt-commons/jolt-metrics/releases) has
binaries for macOS (arm64), Linux (x86_64) and Windows (x86_64). Unpack one and
put `jolt-metrics` on your PATH:

```console
tar xzf jolt-metrics-v0.7.1-aarch64-macos.tar.gz
cp jolt-metrics-v0.7.1-aarch64-macos/jolt-metrics ~/.local/bin/
xattr -d com.apple.quarantine ~/.local/bin/jolt-metrics   # macOS only: the binary is unsigned
```

```console
jolt-metrics src                # print the Markdown report
jolt-metrics src report.md      # write it to a file
jolt-metrics src report.html    # one page to share; the extension picks the format
jolt-metrics src --format json  # the lean report, for tools and LLMs
jolt-metrics src --format edn   # the full result, as data
jolt-metrics --help
```

To build it from a checkout: `jolt build -m metrics.cli -o jolt-metrics`.

### With jolt

In a checkout of this repository, use `jolt -m metrics.cli` in place of
`jolt-metrics`:

```console
jolt -m metrics.cli src                # print the Markdown report
jolt -m metrics.cli src report.html    # write the HTML page
jolt -m metrics.cli --help
```

Anywhere else, pull it in as a git dependency for one run:

```console
jolt -Sdeps '{:deps {io.github.jlt-commons/jolt-metrics {:git/url "https://github.com/jlt-commons/jolt-metrics" :git/tag "v0.7.1" :git/sha "d134ab1"}}}' \
  -m metrics.cli src
```

or add an alias to a project's `deps.edn`:

```clojure
{:aliases
 {:metrics {:extra-deps {io.github.jlt-commons/jolt-metrics
                         {:git/url "https://github.com/jlt-commons/jolt-metrics"
                          :git/tag "v0.7.1" :git/sha "d134ab1"}}
            :main-opts ["-m" "metrics.cli"]}}}
```

```console
jolt -M:metrics src report.html
```

### Options

| Option | Default | Meaning |
|---|---|---|
| `--format md\|edn\|json\|html\|sarif` | from the output file, else `md` | Markdown for reading, EDN with the full result, JSON for tools, HTML to share, SARIF for code scanning |
| `--top N` | 25 | queue entries to show in Markdown and HTML |
| `--days N` | 90 | how much git history counts as recent churn |
| `--commits N` | 500 | how many recent commits to check for unusual changes |
| `--no-git` | | skip git history, so no churn, hotspots or unusual changes |
| `--no-gitignore` | | analyze files git ignores too; they're left out by default |
| `--no-abstractions` | | skip the search for abstraction opportunities, the slowest pass |
| `--exclude GLOB` | | leave out matching files, relative to the directory; can be repeated |
| `--config FILE` | `<dir>/.jolt-metrics.edn` | the config file |

Without `--format`, an output file ending in `.md`, `.edn`, `.json`, `.html`,
`.htm` or `.sarif` (any case) gets that format. Anything else, or no output file, gets
Markdown.

The command exits 2 on bad usage or a missing directory. An unreadable file
doesn't fail the run; the report lists it under `Files not analyzed`. Source is
never evaluated: `#=` is a read error.

### Configuration

Put project settings in `.jolt-metrics.edn` in the analyzed directory:

```clojure
{:exclude ["vendor/**" "target/**" "src/parser_generated.clj"]
 :test-paths ["test" "bench"]
 :api-namespaces ["my.lib" "my.lib.api.*"]
 :api-paths ["src/my/lib/core"]
 :thresholds {:cyclomatic {:warning 8 :critical 15}
              :duplicates {:max-distance 0.3}}
 :linters {:missing-docstring {:level :warning}
           :missing-else-branch {:level :off}}}
```

`:exclude` patterns are relative to that directory, and `--exclude` adds to
them. `*` stays within a directory, `**` crosses directories, and a bare
directory covers its contents.

Inside a git repository, files git ignores are left out, whether through
`.gitignore`, `.git/info/exclude` or global excludes. Tracked files always
count. `--no-gitignore` turns this off; `--no-git` doesn't, since it only skips
history. Outside a repository, or without git, every file is read. Nothing else
is excluded by default, so tracked vendored and generated code counts until you
exclude it.

Files under `:test-paths` (default `test` and `tests`, as a directory at any
depth, or a glob) are measured but never queued or called unused.

`:api-namespaces` lists namespaces called from outside the analyzed code, so
their public functions are never reported as unreferenced. An entry is a
namespace name or a glob where `*` matches any characters, dots included:
`"my.lib.*"` covers `my.lib.core` and `my.lib.core.impl` but not `my.lib`.
`:api-paths` does the same by file path, with `:exclude`-style globs, for files
with no `ns` form. Private functions in either are still checked.

#### Thresholds

`:thresholds` overrides the report's limits key by key, so
`{:cyclomatic {:warning 8}}` keeps the critical limit of 20. Every format shows
the limits it used: the Markdown and HTML Thresholds tables, `:thresholds` in
EDN, and `thresholds` (snake_case) in JSON.

| Key | Default | Meaning |
|---|---|---|
| `:cyclomatic` | `{:warning 10 :critical 20}` | a function over `:warning` is a warning, over `:critical` critical |
| `:cognitive` | `{:warning 15 :critical 25}` | the same, for cognitive complexity |
| `:nesting` | `{:warning 3 :critical 5}` | the same, for nesting depth |
| `:lines` | `{:warning 20 :critical 50}` | the same, for a function's source lines |
| `:params` | `{:warning 4 :critical 7}` | the same, for a function's parameters |
| `:maintainability` | `{:warning 38.0 :critical 10.0}` | lower is worse: at or under `:warning` is a warning, under `:critical` critical |
| `:file-lines` | `{:warning 500 :critical 1000}` | a file over these source lines |
| `:efferent` | `{:warning 19 :critical 39}` | a file depending on more project files than these |
| `:hotspot` | `{:warning 0.5 :critical 0.8 :min-changes 3 :changes-floor 20 :complexity-floor 100}` | hotspot risk from `:warning` up is a warning, from `:critical` up critical; a file needs `:min-changes` recent changes to have a risk at all; the floors are the least changes and file complexity risk is scaled by (see [Hotspots](#hotspots)) |
| `:unusual` | `{:z 3.0}` | how many standard deviations make a commit or a file unusual |
| `:duplicates` | `{:max-distance 0.35 :min-tokens 40}` | the largest compression distance a near-duplicate pair can have, and the fewest tokens a definition needs to be compared |
| `:abstractions` | `{:min-size 8 :max-arity 4}` | the fewest syntax nodes an abstraction opportunity can have, and the most parameters it can take |

Count limits are whole numbers, hotspot limits and `:max-distance` lie between 0
and 1, and `:z` is positive. A `:warning` past its `:critical` (the other way
round for `:maintainability`) is an error, and so is an unknown key, so a typo
can't silently keep a default. A bad config stops the run with exit code 2 and
says what's wrong.

Raising `:unusual :z` or lowering `:duplicates :max-distance` gives fewer,
surer findings.

#### Lint rules

`:linters` sets a lint rule's level: `:off`, `:info`, `:warning` or `:error`.
The rules, their messages and their default levels come from
[clj-kondo](https://github.com/clj-kondo/clj-kondo/blob/master/doc/linters.md),
so its documentation describes each one. An unknown rule or level stops the run
with exit code 2.

| On by default | Off by default |
|---|---|
| `redundant-do`, `redundant-let`, `cond-else`, `constant-condition`, `missing-else-branch`, `missing-body-in-when`, `missing-clause-in-try`, `single-operand-comparison`, `single-logical-operand`, `not-empty?`, `inline-def`, `loop-without-recur`, `case-duplicate-test` (error), `earmuffed-var-not-dynamic`, `docstring-blank`, `misplaced-docstring`, `refer-all`, `use`, and at `:info` `redundant-nested-call`, `redundant-str-call`, `redundant-ignore` | `redundant-let-binding`, `if-nil-return`, `if-x-x-y`, `redundant-call`, `redundant-fn-wrapper`, `equals-true`, `equals-false`, `equals-nil`, `plus-one`, `minus-one`, `single-key-in`, `dynamic-var-not-earmuffed`, `def-fn`, `missing-docstring`, `docstring-no-summary`, `docstring-leading-trailing-whitespace`, `unsorted-required-namespaces` |

clj-kondo resolves every var; these rules match a call's head by name, with a
`clojure.core/` qualifier removed, so a local that shadows a core name can make
a false finding. Quoted forms and `comment` blocks aren't linted, and a
threading macro's steps count the threaded value as an argument.

#### Ignore markers

As with clj-kondo's `#_:clj-kondo/ignore`, a marker before a form leaves that
form's findings out of the report:

```clojure
#_:jolt-metrics/ignore
(defn parse [tokens] ...)                    ; no lint, queue or cleanup findings

#_{:jolt-metrics/ignore [:lines :redundant-do]}
(defn render [result] ...)                   ; only those rules
```

A marker covers lint findings inside the form, the queue rules a definition
starting in it broke (`cyclomatic`, `lines`, `params` and so on, the rule names
in the queue), and the `unused-private` and `unreferenced-public` cleanup
candidates. A marker that leaves nothing out is itself a `redundant-ignore`
finding. `#_:clj-kondo/ignore` covers lint findings too, and is never redundant,
since clj-kondo may need it for rules of its own.

### The report

1. Summary: finding counts and project totals.
2. Files not analyzed: anything unreadable, with the reason.
3. Refactoring queue: functions and files over a threshold, worst first, each
   with its location, line range, numbers (broken ones in bold) and what to do.
   Tests aren't queued. A function's score is weighted by its reach, so a
   complex function everything goes through ranks above an equally complex one
   nothing calls. The weight rises on a log scale from 1 with no dependents to
   2 at 100 and stays there; 100 is our choice.
4. [Hotspots](#hotspots): complex files that keep changing. Their contents rank
   higher in the queue.
5. [Unusual changes](#unusual-changes): commits that put far more information
   into the code, or took far more out, than the ones before them.
6. [Unusual files](#unusual-files): files whose words are far more surprising
   than the project's other files'.
7. [Near-duplicates](#compression-and-near-duplicates): pairs of definitions
   that are near-copies, closest first.
8. [Abstraction opportunities](#abstraction-opportunities): code shapes that one
   function could replace, with holes where the uses differ, most saving first.
9. Cleanup candidates: unused requires, and functions nothing mentions. A
   require counts as used when anything it brings in is, so an idle alias next
   to a used `:refer` isn't listed, but unused referred names are. Matching is
   by name and can't see runtime lookups or outside callers, so check public
   functions before removing them, and list your API namespaces in the config.
10. [Lint findings](#lint-rules): idioms with a simpler form and likely bugs,
    each with its rule and level.
11. Files and Architecture: per-file numbers, coupling and dependencies.
12. Thresholds: what the severities mean.

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

These are the defaults (see [Thresholds](#thresholds)). The function length
limit sits between the Clojure style guide and general-purpose tools: in the
Clojure projects we measured, the guide's 10 lines flags a quarter of all
functions, and 20 flags about one in ten. All of these are rules of thumb; even
NIST calls the exact number "somewhat controversial".

### Hotspots

A file's risk combines how often it changed in the last `--days` (default 90)
with its cyclomatic complexity, each scaled to 0..1. As in ast-metrics, risk is
1 minus the distance to the corner where both are highest. ast-metrics scales
by the project's own maxima, so the file with the most changes and complexity
always scores 1.0, a critical, however modest its numbers. In a young project
that's the central file, and refactoring can't clear it because every refactor
is another change ([#14](https://github.com/jlt-commons/jolt-metrics/issues/14)).

So each axis is scaled by the project's maximum or a floor, whichever is
higher: 20 changes in the window, and a file cyclomatic complexity of 100. To
reach the corner, a file has to change as often as a busy project's busiest
files and be as complex as the top tenth of files in the mature Clojure
projects we measured (clojure-lsp, babashka, newsroom). Projects whose top
files pass the floors score as before. A day-old library's central file, at 5
changes and complexity 113, has risk 0.25 rather than 1.0.

`:changes-floor` and `:complexity-floor` under `:hotspot` change the floors;
set both to 0 for the purely relative score. With a much shorter `--days`,
lower `:changes-floor` to match.

### Unusual changes

This follows Torres, Baltes, Treude and Wagner, [Information-theoretic detection
of unusual source code changes](https://doi.org/10.1007/s10664-025-10644-y)
(Empirical Software Engineering, 2025). Source is read as a stream of words,
with identifiers split at case changes and non-letters, so `createUser` and
`create-user` are both `create` and `user`. A file's entropy is the Shannon
entropy of its word frequencies in bits, comments included. A commit's change
is the sum of its files' entropy changes, and its z-score is how many standard
deviations that is from the mean of every earlier commit. Commits with at least
10 before them and a z-score over 3 in either direction are listed, most
unusual first.

Only files the report measures count, minus tests, which the paper found to be
a source of false positives along with generated code (exclude that in the
config). History follows the first parent, so a merged pull request is one
change, and commits that touch no analyzed code aren't in the baseline.

On the paper's 95 Java projects, 58% of flagged commits were feature work
maintainers would want to look at, or 83% counting feature removals and
porting; most of the rest were dead-code removal, build changes and file
conversions. Large swings are mostly features landing and rarely bugs, so use
the list to decide where to look closer in review. The paper got its best
precision using all history as the baseline, so every commit in the
`--commits` range counts.

Reading 500 commits takes a few seconds on a typical project, more when the
commits touch large files.

### Unusual files

The same words give each file an `:entropy`. Their average in the summary is
what the paper calls the entropy spread: if it keeps climbing, files are taking
on more concepts instead of new files being made for them.

A file's `:surprisal` is the cross-entropy of its words against the rest of the
project: the average bits per word under every other file's word distribution,
with one added to each count so a word found nowhere else has a finite cost.
Raw entropy mostly tracks file size; surprisal is per word, so it doesn't. A
file more than 3 standard deviations above the mean is listed. These files use
a different vocabulary from the rest of the project: on babashka, a generated
table of XML entities and a standalone example; on clj-kondo, a vendored copy
of tools.reader. Exclude such code once you've seen it, or move a concern that
belongs elsewhere.

Tests and files under 50 words get no surprisal and don't count toward the
distribution. Add fixture directories like `test-resources` to `:test-paths`.
The paper proposed cross-entropy but didn't evaluate it, so there's no
published precision for this list.

### Compression and near-duplicates

The Kolmogorov complexity of code is the length of the shortest program that
prints it. It can't be computed, but any compressor gives an upper bound, and
the literature uses one in its place. Here that's DEFLATE (gzip's algorithm) at
its best level, without a header.

Jbara and Feitelson, [Quantification of code regularity using preprocessing and
compression](https://www.cs.huji.ac.il/~feit/papers/RegMet14.pdf) (2014),
compared five compressors and four preprocessing levels and found the
preprocessing decides the result. So code is compressed as a token stream:
layout, comments and docstrings are dropped, locals are renamed by first
appearance (`$0`, `$1`, ...), and strings and regexes become placeholders.
`(defn mean [xs] ...)` and `(defn avg [vs] ...)` with the same body give the
same tokens.

Each definition has a `:compressed-size` in bytes. Each file has one for all its
code, plus a `:redundancy`: the share of its token stream compression removes,
from 0 to 1. Files of boilerplate or copies rate high. The summary averages it
over non-test files.

Two definitions are near-duplicates when their normalized compression distance
(Li, Chen, Li, Ma and Vitányi, [The similarity
metric](https://arxiv.org/abs/cs/0111054), 2004),

```
NCD(x, y) = (C(xy) - min(C(x), C(y))) / max(C(x), C(y))
```

is at most 0.35. NCD is 0 when one compressed says everything about the other,
and near 1 when it says nothing. Ishio, Maeda, Shibuya and Inoue, [Cloned buggy
code detection in practice using normalized compression
distance](https://naist-se.github.io/pastpublications/naistse-1259.pdf), use
the same measure over DEFLATE-compressed token streams to find clones, and NEC
deployed it. We picked 0.35 by reading the pairs found in clojure-lsp: at that
distance two definitions share most of their code and differ in a call, a
keyword or a few arguments. Definitions under 40 tokens are skipped, because
compression overhead dominates and short repeats are idioms. Tests are skipped
too, as are pairs where one definition is a helper inside the other.

Comparing every pair is quadratic, so two cheap tests rule out most pairs
before compressing anything. NCD is at least the size gap over the larger size,
so definitions that compress to very different sizes can't match. Then a
MinHash estimate of shared token trigrams skips pairs under 12%. Being an
estimate, it can miss a pair that only just qualifies, but never identical
token streams. On clojure-lsp's 1334 functions it found the same 54 pairs as
the full comparison, in about a second of CPU time; the rest of the analysis
takes about six.

### Abstraction opportunities

Where near-duplicates pair whole definitions, this finds code that recurs
anywhere in the project above a minimum size, with variables where the uses
differ:

```
(z/find-token ?a (fn [?b] (= ?c (z/string ?b))))    ; 9 uses, 2 parameters
```

The method is babble's (Cao, Kunkel, Nandi, Willsey, Tatlock and Polikarpova,
[babble: Learning better abstractions with e-graphs and
anti-unification](https://arxiv.org/abs/2212.04596), POPL 2023):

1. Every definition goes into one e-graph (`metrics.egraph`, built as in Willsey
   et al., [egg: Fast and extensible equality
   saturation](https://arxiv.org/abs/2004.03082), POPL 2021), which shares
   equal subterms. Rewrites that undo sugar run to a fixpoint: `->`, `->>`,
   `when`, `when-not`, `if-not` and an explicit `nil` else. Code written with
   `->` and the same code as nested calls end up in one e-class.
2. Pairs of e-classes are anti-unified: the most specific pattern both fit is
   found by trying every pair of their nodes with the same operator, so either
   spelling works. As in Plotkin's original, the same pair of differing
   subterms always gets the same variable. Locals always become variables,
   since their names mean nothing outside their definition, and a variable in
   a binding position is a local of the new function, not a parameter. Patterns
   that need a variable for a whole binding vector or parameter list are
   dropped; that's a macro's job.
3. Each pattern is matched over the e-graph to count its uses and scored as
   Stitch does (Bowers et al., [Top-down synthesis for library
   learning](https://arxiv.org/abs/2211.16605), POPL 2023): each use saves the
   pattern's size minus a two-node call, and the new definition costs the
   pattern plus a node per parameter plus three. Patterns are taken greedily,
   most saving first, and the code each one covers is used up, so its
   generalizations and pieces don't reappear as separate entries.

Comparing every pair of e-classes is quadratic, so only classes with the same
operator and head are compared, each against its next 8 neighbours in a sort by
shape. This is the sorted-neighbourhood method from record linkage (Hernández
and Stolfo, [The merge/purge problem for large
databases](https://dl.acm.org/doi/10.1145/223784.223807), 1995). Copies sort
together, so a pattern with several uses very likely has some pair compared.
Strings keep their text here, unlike in compression, because an extracted
function would need a differing string as a parameter.

Savings count syntax nodes and are a lower bound: nodes only a rewrite created
count for nothing, so patterns are shown as written. Tests are skipped, and the
best 50 are kept.

On clojure-lsp's 1435 definitions the search takes about 5 seconds, most of the
report's time; `--no-abstractions` skips it. Its top finding there is the
code-action map `{:command {:arguments ?a :command ?b :title ?c} :kind ?d :title
?c}`, built by hand 33 times.

### JSON

`--format json` is the lean report: what to act on, in a stable shape. It
leaves out per-function numbers for functions that break nothing, Halstead
detail and imports, which EDN keeps. On this repository it's about 10 KB to
EDN's 130 KB.

```json
{"tool": "jolt-metrics", "schema_version": 1,
 "summary": {...}, "findings": [...], "hotspots": [...], "duplicates": [...],
 "abstractions": [...],
 "thresholds": {...}, "cleanup": {...}, "lints": [...],
 "errors": [...], "files": [...], "dependencies": [...]}
```

| Key | Holds |
|---|---|
| `schema_version` | 1; it goes up when a key is removed or changes meaning |
| `summary` | `critical`, `warnings`, `files`, `files_not_analyzed`, `loc`, `sloc`, `cloc`, `functions`, `cyclomatic_total`, `cyclomatic_avg`, `cognitive_total`, `maintainability_avg`, `instability_avg`, `entropy_avg`, `redundancy_avg`, `duplicates` (how many pairs), `abstractions` (how many; `null` under `--no-abstractions`), `lints` (how many) |
| `findings` | the whole queue, worst first and never capped by `--top`: `rank`, `severity`, `kind`, `name`, `path`, `line`, `end_line`, `changes`, `score`, `metrics`, `violations` (`rule`, `value`, `limit`, `band`), `actions` |
| `hotspots` | `path`, `changes`, `complexity`, `risk`; `null` under `--no-git` |
| `unusual_changes` | `commits` (how many were checked) and `changes`, most unusual first: `commit`, `date`, `subject`, `delta` (bits), `z`, `files`; `null` under `--no-git` |
| `unusual_files` | `path`, `surprisal` (bits a word), `z`, `words`, most surprising first |
| `duplicates` | `distance`, and `a` and `b`, each `path`, `name`, `line`, `end_line`; closest first |
| `abstractions` | `pattern` (as Clojure text), `size`, `arity`, `uses`, `saving`, and `sites`, each `path`, `name`, `line`; most saving first; `null` under `--no-abstractions` |
| `thresholds` | the limits the report used, as in [Thresholds](#thresholds), keys in snake_case: `cyclomatic`, ..., `file_lines`, `hotspot` (`warning`, `critical`, `min_changes`), `unusual` (`z`), `duplicates` (`max_distance`, `min_tokens`), `abstractions` (`min_size`, `max_arity`) |
| `cleanup` | `unused_requires` (`path`, `lib`, `alias`, `refers`), `unused_private` and `unreferenced_public` (`path`, `name`, `line`) |
| `lints` | `path`, `line`, `column`, `rule`, `level`, `message`, in source order; ignored findings and rules that are `:off` left out |
| `errors` | `path`, `error` for each file not analyzed |
| `files` | `path`, `language`, `namespace`, `loc`, `sloc`, `cloc`, `cyclomatic`, `cognitive`, `maintainability`, `efferent`, `afferent`, `instability`, `instability_band`, `changes`, `entropy`, `surprisal`, `compressed_size` (bytes), `redundancy` |
| `dependencies` | `from`, `to` |

Keys are snake_case and always present, and so are values that were keywords
(`"file_lines"`). Lists are empty, never missing. A function finding's
`metrics` has `cyclomatic`, `cognitive`, `nesting`, `lines`, `params`,
`maintainability`, `fan_in`, `fan_out` and `reach` (the last three `null`
outside Clojure); a file finding's is `null`. Non-integers are rounded to 3
decimals, and values JSON can't hold (NaN, infinity) are `null`.

### HTML

`--format html` writes a single self-contained file with the Markdown report's
sections as tables. CSS and script are inline and nothing is fetched, so you
can email it or drop it in a chat. It follows the system's light or dark theme,
with a button to switch. The script sorts the Files, Coupling, Hotspots and
Unusual changes tables by any column, filters files by path and the queue by
severity, collapses sections, and offers a Download JSON button for the lean
JSON embedded in the page. Without the script, the tables are all still there;
only the controls are hidden.

### SARIF

`--format sarif` writes a [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html)
log for code scanning, such as GitHub's: each lint finding at its level, each
rule a queue entry broke (`error` when critical, else `warning`), and the
cleanup candidates as notes. Paths are as the analyzer saw them, so run it from
the repository root (`jolt-metrics src out.sarif`) for paths a scanner can
place. An unused require has no line of its own and points at line 1.

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

;; each commit's entropy change makes unusual changes
(core/analyze-dir "src" {:history (git/history "src" 500 #(core/code-path? "src" {} %))})

;; the Markdown report
(report/render result)

;; the lean JSON and the HTML page
(require '[metrics.json :as json] '[metrics.html :as html])
(json/render result)   ; text; (json/report result) is the same as data
(html/render result {:top 25})
```

A result has `:files`, `:errors`, `:edges`, `:summary`, `:queue`, `:hotspots`,
`:unusual-changes`, `:unusual-files`, `:cleanup` and `:lints`. `analyze-files`
and `analyze-dir` take `:linters` to set rule levels, as in the config. Each file has `:loc`,
`:cyclomatic`, `:cognitive`, `:halstead`, `:entropy`, `:surprisal`, `:words`,
`:maintainability-index-without-comments`, `:functions` (per-definition
metrics), and the coupling fields `:efferent-coupling`, `:afferent-coupling`,
`:instability` and `:instability-band`.

## Architecture

The metric math is pure and independent of the parser:

```
source ──► normalized model ──► pure metric visitors ──► queue ──► report
  (reader, scanner)             halstead, mi, loc,       (queue,   (report)
                                cyclomatic, cognitive,   cleanup)
                                coupling, hotspot,
                                entropy, compression
```

- `metrics.reader` parses Clojure/Jolt forms into a normalized model: each
  definition's decision kinds, cognitive flow tree, Halstead operators and
  operands, logical LOC, and the file's imports. Every metric walks forms
  through one function, so they all see the same code: collections are code;
  quoted data, `comment` forms and `case` constants aren't. Syntax-quote is
  read back, so macros are measured as written. `#?` reader conditionals are
  read for the running platform, so other platforms' branches aren't measured.
- `metrics.scanner` lexes the raw text for what the reader drops: docstrings,
  discards, and where each list ends.
- `metrics.*` metric namespaces are pure functions over the model. They never
  read files or mention writ.
- `metrics.tokens` turns forms into the token stream compression measures.
  `metrics.deflate` (the compressor) and `metrics.minhash` (the pair
  pre-filter, on arrays for speed) are host code used by `metrics.compression`,
  kept separate so what writ checks stays pure.
- `metrics.core` parses, computes per-definition and per-file metrics, resolves
  coupling across files, and rolls up the project.
- `metrics.callgraph` builds a [jolt-lsp](https://github.com/jlt-commons/jolt-lsp)
  index from the non-test Clojure files and derives each function's fan-in,
  fan-out and reach from resolved references between vars. Passing a function
  counts as a reference, the same as calling it.
- `metrics.queue` and `metrics.cleanup` turn the numbers into work.
- `metrics.lint` finds idioms and likely bugs in read forms; it's the Clojure
  adapter's `:lint`. `metrics.scanner` finds ignore markers, and
  `metrics.ignore` drops what they cover from the lint findings, the queue and
  the cleanup candidates.
  `metrics.thresholds` holds the limits: the defaults merged with the project's
  own, and validated.
- `metrics.git` reads churn and each commit's entropy change. It's the only
  part that shells out.
- `metrics.report` renders Markdown, `metrics.json` the lean JSON, and
  `metrics.html` the HTML page, inlining `resources/metrics/html/report.css`
  and `report.js`, and `metrics.sarif` the SARIF log.

A definition is a `defn`, `defn-`, `defmacro`, `defmethod`, `deftest`, a method
of `defrecord`, `deftype`, `extend-type` or `extend-protocol`, or a `def`,
`defonce` or `defmulti` of an `fn`. Other top-level code counts toward its
file's complexity.

### Languages

The pipeline only sees a language adapter (`metrics.language`) that says how to
parse and scan a language. Parsing has to produce the forms the shared walker
understands, so every language gets the same metrics from the same code. To add
a language, write an adapter and register it there.

## Scheme

`.ss`, `.scm`, `.sls` and `.sps` files are analyzed as Scheme, targeting Chez
and Gambit; other dialects work as far as they share that syntax. A directory
with both languages gets one report. The code lives under `metrics.scheme`:

- `metrics.scheme.scanner` defines Scheme tokens: a comma is unquote, a bracket
  is a paren, and `#` covers block and datum comments and Gambit's `#!void`.
- `metrics.scheme.reader` parses tokens into forms. It only parses, so nothing
  in the source can run.
- `metrics.scheme.normalize` rewrites Scheme into the walker's shapes, and is
  the one place that decides what Scheme means.
- `metrics.scheme.deps` finds what a file depends on.

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

Every other list is a call. A name that would read as a Clojure form, like the
`loop` in `(let loop ...)`, stays a call, and so does a form too malformed to
translate, as can happen in a macro template.

Files depend on each other through `load`, `include`, `##include`, and `import`
of a `library`. A load path can be relative to its file or the analyzed root,
or match the unique file ending that way. Paths built at runtime can't be
resolved, and ambiguous ones make no edge.

Public functions aren't reported as unreferenced in Scheme, because runtimes
reach names from emitted code and strings. There's no `private` to check, and
no unused-import check yet. Top-level code like `(let () (define ...))` counts
toward its file but isn't queued.

## Maintainability index

The index is `171 - 5.2*ln(V) - 0.23*G - 16.2*ln(lines)`, scaled to 0..100 and
clamped at 0. A definition uses its source lines rather than a form count,
which would overstate nested expressions. A file uses the average of its
definitions, as the original formula does; a sum would just measure file
length. The report ranks on this comment-free score. `:maintainability-index`
adds the comment term, can exceed 100, and is informational.

## Named helpers

Named inner helpers are measured on their own. Each function a `letfn` binds is
a unit named `outer/helper` with its own complexity, nesting, lines and
parameters, and kind `:helper`; its own helpers are `outer/helper/inner`. The
outer function keeps only its own code and lines. An anonymous `fn` has no name
to stand under, so it stays part of the enclosing function. In Scheme, a
`define` of a function inside another and a lambda bound by `letrec` or
`letrec*` are helpers too; a lambda bound by plain `let` isn't, and a named
`let` is a loop. Helpers are never cleanup candidates, and a file's total
complexity counts each one as a function.

## The decision table

Cyclomatic complexity counts one decision point per:

- `if if-not if-let if-some when when-not when-let when-some when-first`: 1
- `cond`: 1 per clause; a trailing keyword or `true` test is the free default
- `condp`: 1 per clause; a lone trailing form is the free default
- `case`: 1 per case except the default
- `cond-> cond->>`: 1 per clause
- `and or`: 1 per operator, so n-1 for n arguments
- `for doseq`: 1 per binding, 1 per `:when` or `:while`
- `dotimes while loop`: 1
- `catch`: 1 (`try` itself is not a branch)

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

Two differences from the paper: a plain `else` is free, since nearly every
Clojure `if` has one, and recursion adds nothing, where the paper adds 1 per
function in a cycle.

## Tests

```console
jolt -M:test                      # everything
jolt -M:test metrics.json-test    # only the namespaces named
```

Each metric namespace has a writ spec in `test/metrics/*_spec.clj` stating its
contract: signatures, a state graph, a model law, and worked examples. The
`*_test.clj` files cover the rest with `clojure.test`, and `walker_test.clj`
pins what the reader counts with golden examples. Specs are found by scanning
the directory, so a new `*_spec.clj` is picked up automatically. To run them
against a writ checkout at `../writ` instead of the pinned version, add the
`:dev` alias: `jolt -M:test:dev`.

`self_test.clj` runs the report on `src` and fails if anything is queued, so a
change that crosses a threshold has to refactor or argue the threshold down.

## CI and releases

Every push to `main` and every pull request runs the tests. If they pass, CI
runs the report on `src`, saves `report.html`, `report.json` and `report.md` as
the `metrics-report` artifact, and shows the numbers on the run's summary page.

Every run also builds the binary on macOS, Linux and Windows and runs
`ci/smoke.sh` against the packaged archive, with jolt's dependency caches
hidden and, on Windows, MSYS2 off the PATH. A missing resource or DLL fails CI
instead of a user's machine. The archives are kept with the run.

Releases only happen after the tests pass. Either run the `ci` workflow from
the Actions tab (*Run workflow* on `main`, with a version like `v0.2.0`) and CI
tags the commit and creates the release, or push a version tag on a commit
that's on `main`, for example `git tag v0.2.0 && git push origin v0.2.0`.

The release gets notes generated from merged pull requests, with the three
binaries and three report files attached. It's refused when the version isn't
`vX.Y.Z`, the tag or release already exists, or the run or commit isn't on
`main`. A tag pushed with failing tests or a failing build stays a plain tag.

## Licence

Copyright (c) 2026 Dmitri Sotnikov and Burin Choomnuan.

Distributed under the [Eclipse Public License 2.0](LICENSE). SPDX identifier:
`EPL-2.0`.

The lint rules (`metrics.lint`), the ignore markers and the SARIF output
(`metrics.sarif`) are adapted from [clj-kondo](https://github.com/clj-kondo/clj-kondo),
Copyright (c) Michiel Borkent, distributed under the Eclipse Public License 1.0.
