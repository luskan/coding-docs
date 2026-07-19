#!/usr/bin/env bash
# Build the Hilt tutorial EPUB.
#
# Design constraint: each EPUB section is exactly one source .md file.
# We pass the files in reading order and split the book at level-1 headings.
# Every source file has exactly one real `# ` heading at its top (the only
# other `# ` lines live inside ```toml code fences), so pandoc emits one
# XHTML content document per input file. This is *enforced* after the build
# (see the 1:1 assertion below) so a future edit can't break it silently.
set -euo pipefail
cd "$(dirname "$0")"

# Single source of truth for the edition. Bump VERSION to cut a new build:
# it names the output file and prints on the title page.
VERSION="1"
YEAR="2026"
OUT="Hilt-Tutorial-v${VERSION}.epub"

# Reproducible builds: pin the EPUB's `dcterms:modified` to the last commit
# time (falls back to a fixed epoch outside a git checkout). Combined with the
# fixed `identifier` in epub-metadata.yaml, rebuilds are byte-stable.
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct 2>/dev/null || echo 1700000000)}"

# Reading order: each part immediately followed by its practice section.
FILES=(
  HILT_1_SETUP.md                       HILT_1_SETUP_PRACTICE.md
  HILT_2_BASICS.md                      HILT_2_BASICS_PRACTICE.md
  HILT_3_QUALIFIERS.md                  HILT_3_QUALIFIERS_PRACTICE.md
  HILT_4_SCOPES.md                      HILT_4_SCOPES_PRACTICE.md
  HILT_5_VIEWMODELS.md                  HILT_5_VIEWMODELS_PRACTICE.md
  HILT_6_TESTING.md                     HILT_6_TESTING_PRACTICE.md
  HILT_7_ENTRYPOINTS_LAZY.md            HILT_7_ENTRYPOINTS_LAZY_PRACTICE.md
  HILT_8_COROUTINES_DISPATCHERS.md      HILT_8_COROUTINES_DISPATCHERS_PRACTICE.md
  HILT_9_WORKMANAGER.md                 HILT_9_WORKMANAGER_PRACTICE.md
  HILT_10_MULTIMODULE.md                HILT_10_MULTIMODULE_PRACTICE.md
)

# Optional cover: dropped-in cover.png / cover.jpg is wired up automatically.
COVER_ARGS=()
for c in cover.png cover.jpg cover.jpeg; do
  if [[ -f "$c" ]]; then COVER_ARGS=(--epub-cover-image="$c"); break; fi
done

# Scratch space for the generated template and the post-build unpack.
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

# Title page: pandoc's default template only exposes title/subtitle/author/
# date, and its OPF <dc:date> is derived from `date` (must be a real date, so
# we keep it the bare YEAR). To show the edition on its own line, we take the
# stock epub3 template and inject a `$version$` line after the date -- so the
# page shows "Version N" while <dc:date> stays valid. Generating from the
# installed pandoc keeps the template from going stale.
TEMPLATE="$scratch/title.epub3"
pandoc --print-default-template=epub3 \
  | awk '
      /<p class="date">\$date\$<\/p>/ { print; hit=1; next }
      hit && /^\$endif\$/ {
        print
        print "$if(version)$"
        print "  <p class=\"version\">Version $version$</p>"
        print "$endif$"
        hit=0; next
      }
      { print }
    ' > "$TEMPLATE"

pandoc \
  --from=gfm \
  --to=epub3 \
  --split-level=1 \
  --toc --toc-depth=1 \
  --lua-filter=epub-links.lua \
  --metadata-file=epub-metadata.yaml \
  --metadata=date="$YEAR" \
  --template="$TEMPLATE" \
  --variable=version="$VERSION" \
  --css=epub.css \
  ${COVER_ARGS[@]+"${COVER_ARGS[@]}"} \
  --output="$OUT" \
  "${FILES[@]}"

# --- Post-build verification -------------------------------------------------
# Enforce the "one section per .md file" contract and catch any dead link that
# slipped through, so a silently-wrong book never ships.
work="$scratch/pkg"
unzip -q "$OUT" -d "$work"

n_in=${#FILES[@]}
n_ch=$(find "$work"/EPUB/text -name 'ch*.xhtml' | wc -l | tr -d ' ')
if [[ "$n_in" -ne "$n_ch" ]]; then
  echo "FATAL: $n_in source files but $n_ch EPUB sections -- the 1:1 mapping broke." >&2
  echo "       (likely a second '# ' heading or an unbalanced code fence in a source file)" >&2
  exit 1
fi

# No href may point outside the package: allow only http(s)/mailto and in-page
# or cross-chapter '#'/'ch*.xhtml' targets. Anything else is a dead link.
dead=$(grep -rhoE '<a [^>]*href="[^"]+"' "$work"/EPUB/text \
        | grep -oE 'href="[^"]+"' \
        | sed -E 's/^href="//; s/"$//' \
        | grep -vE '^(https?:|mailto:|#|ch[0-9]+\.xhtml)' || true)
if [[ -n "$dead" ]]; then
  echo "FATAL: dead/unpackaged links remain in the EPUB:" >&2
  echo "$dead" | sort -u >&2
  exit 1
fi

# The version line must actually render on the title page (guards against the
# template injection silently failing if pandoc's default template changes).
if ! grep -q "Version ${VERSION}" "$work"/EPUB/text/title_page.xhtml; then
  echo "FATAL: 'Version ${VERSION}' did not render on the title page." >&2
  exit 1
fi

# The OPF <dc:date> must be a real, non-empty date (pandoc silently empties it
# if `date` is not parseable).
if grep -qE '<dc:date[^>]*></dc:date>' "$work"/EPUB/content.opf; then
  echo "FATAL: <dc:date> is empty in the OPF (invalid date metadata)." >&2
  exit 1
fi

# Only chapter-level <h1> may remain in the content; sub-headings are demoted to
# .fauxhead blocks so nothing but the 20 chapters can reach a reader's TOC.
if grep -rqE '<h[2-6][ >]' "$work"/EPUB/text; then
  echo "FATAL: a sub-heading (<h2>-<h6>) survived in the content; TOC would list it." >&2
  exit 1
fi

echo "Wrote $OUT  (version $VERSION; $n_ch sections, one per source file; no dead links)"
