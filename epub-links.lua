-- Make the tutorial's cross-file links behave *inside* the EPUB, and flatten
-- the in-chapter heading structure so only chapters show in the TOC.
--
-- 1. The chapter-level (H1) heading gets a stable anchor derived from its title
--    ("2. Hilt basics ..." -> #part-2, "Practice 2 ..." -> #practice-2), so
--    inbound links have something to target. Every sub-heading (H2+) is demoted
--    to a styled, non-heading block (`.fauxhead` in epub.css): it still renders
--    bold and larger, but no reader can surface it in a table of contents or
--    heading-derived navigation.
-- 2. Sibling links to `HILT_<n>_*.md` become internal anchors:
--      * a plain part link      -> #part-<n>
--      * a plain practice link  -> #practice-<n>
--      * a link with a #frag    -> #frag  (the heading id survives the split,
--                                          so a deep link keeps working)
-- 3. Any *other* scheme-less, non-fragment link (e.g. `hilt-practice-app/`, a
--    repo path that is NOT packaged in the EPUB) is unwrapped to plain text,
--    so no dead link ever ships. External URLs (http:, mailto:, ...) and
--    in-page `#` anchors are left untouched.

function Header(el)
  if el.level == 1 then
    local txt = pandoc.utils.stringify(el)
    local practice = txt:match("^Practice%s+(%d+)")
    if practice then
      el.identifier = "practice-" .. practice
    else
      local n = txt:match("^(%d+)%.")
      if n then
        el.identifier = "part-" .. n
      end
    end
    return el
  end

  -- Sub-heading (H2+): demote to a styled, non-heading block so it never
  -- appears in the TOC or heading navigation, yet still reads as bold/larger
  -- text. Keep the original id so any future in-page anchor still resolves.
  return pandoc.Div(
    pandoc.Plain(el.content),
    pandoc.Attr(el.identifier, { "fauxhead", "fauxhead-" .. el.level })
  )
end

function Link(el)
  local target = el.target

  -- Sibling tutorial file, optionally carrying a #fragment.
  local n, rest, frag = target:match("^HILT_(%d+)_(.-)%.md(#?.*)$")
  if n then
    if frag ~= "" and frag ~= "#" then
      el.target = frag                       -- deep link -> keep the section id
    elseif rest:match("PRACTICE$") then
      el.target = "#practice-" .. n
    else
      el.target = "#part-" .. n
    end
    return el
  end

  -- Leftover relative link that is not in the EPUB manifest (no URI scheme and
  -- not an in-page anchor) -> unwrap to its text so it isn't a dead tap.
  if not target:match("^%a[%w+.%-]*:") and not target:match("^#") then
    return el.content
  end

  return el
end
