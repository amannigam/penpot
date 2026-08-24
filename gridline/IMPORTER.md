# Figma importer

Reads a Figma file over the REST API and writes a Gridline file directly. No
plugin, no `.zip`, no per-file clicking.

## How it works

The hard half already existed. Penpot's own file builder lives in this repo at
`common/src/app/common/files/builder.cljc`, and is what `@penpot/library` --
the package the official Figma exporter plugin builds its output with -- is
compiled from. So we drive the same builder the official tooling drives, and
persist with `app.binfile.common/save-file!`, the function binfile import
itself uses.

    Figma REST JSON -> app.common.files.builder -> bfc/save-file!

Everything runs server-side: the document JSON for a real file is large, it
has to be converted and persisted here anyway, and images are fetched here
too. Only *listing* files happens in the browser.

### Code

| Path | Role |
|---|---|
| `backend/src/app/gridline/figma/client.clj` | REST calls: document, image URLs |
| `backend/src/app/gridline/figma/convert.clj` | Figma nodes -> builder calls |
| `backend/src/app/rpc/commands/gridline_figma.clj` | `::import-figma-file`, OAuth exchange |
| `frontend/src/app/main/data/gridline/figma.cljs` | Browser-side listing, OAuth, PKCE |
| `frontend/src/app/main/ui/onboarding/figma_import.cljs` | The picker |
| `backend/test/backend_tests/gridline/figma_convert_test.clj` | 85 assertions, run in CI |

### The two-pass image flow

Media rows reference the file, so they cannot exist before it. The import
therefore:

1. converts (discovery pass, collecting `imageRef`s)
2. saves the file
3. creates media objects from Figma's short-lived S3 URLs
4. converts **again**, with the refs resolved, reusing the same file id
5. updates the file

Converting is pure, so running it twice is cheaper and far less fragile than
rewriting fills inside an already-built file -- Penpot stores fills in a packed
representation that does not welcome surgery.

**The file id must be passed into the second pass.** `fb/add-file` mints a
fresh uuid when it is not given one, and skipping this produced a
`file_migration_file_id_fkey` violation, because the update targeted a row that
was never inserted. There is a regression test.

## What converts

Read from the exporter plugin's transformers rather than inferred from the
REST docs -- the docs were misleading in several places.

**Geometry.** Position from `absoluteTransform[0][2]/[1][2]` and size from the
node's own `size`, never `absoluteBoundingBox` -- that is the post-rotation
envelope, so a rotated node is both offset and oversized. Rotation stores
Penpot's *unrotated* reference point (`applyInverseRotation` about the bounding
box centre) plus `transform` and `transform-inverse`, with the angle from
`acos` of the first matrix element.

**Appearance.** Solid fills and strokes, both reversed -- Figma stacks paints
the opposite way round. Stroke alignment (`INSIDE`/`OUTSIDE` -> inner/outer),
dashed strokes, the largest of per-side stroke weights. Corner radii as
`r1..r4` including per-corner, blend modes, drop and inner shadows in paint
order, layer blur, opacity, locked -> blocked.

**Text.** Family, the `gfont-<slug>` id Penpot resolves against, weight, size,
style, line height *as a ratio of font size*, letter spacing, case, decoration,
and paragraph alignment. One paragraph per line.

**Vectors.** Requested with `?geometry=paths`, parsed by Penpot's own SVG path
parser (`app.common.types.path/from-string`) and translated from node-local to
absolute coordinates. Stroke-only nodes fill their stroke outline, which is
what makes line art arrive as drawn rather than as a block.

**Images.** Resolved through `/v1/files/:key/images` and stored as file media.

**Constraints and auto-layout.** Constraints map straight across. Auto-layout
becomes Penpot flex with gaps, padding, justification, alignment and wrap, plus
per-child sizing and align-self.

## Three counter-intuitive details

All three are taken from the plugin, not invented, and all three look like
bugs until you know why:

1. **Fills, strokes and flex direction are all reversed.** Penpot orders these
   opposite to Figma. Children are added in source order, so `HORIZONTAL`
   becomes `row-reverse` to compensate.
2. **`SPACE_BETWEEN` zeroes the gap.** It carries its own spacing; keeping
   `itemSpacing` would double it.
3. **Padding exactly equal to the dimension has 0.0001 shaved off.** There is a
   Penpot bug otherwise. Same fudge, same reason as the plugin.

## What does not convert

Counted per node and reported to the UI -- never silently dropped.

- **Components.** Instances arrive as plain boards. See ROADMAP.
- Grid layout (flex only)
- Gradients (linear and radial)
- Vector networks beyond flattened path geometry
- Prototyping and interactions
- Effects beyond shadow and layer blur

## Scopes

`folders:read` for listing, `file_content:read` for reading a document. Both
must be ticked on the Figma OAuth app.

Note the docs mislead here too: they present `folders:read` as the successor to
`projects:read`, but the v1 endpoints still demand `projects:read` -- a scope
Figma no longer offers. The **v2** endpoints (`/v2/teams/:id/folders`,
`/v2/folders/:id/files`) are the ones `folders:read` opens, and are what the
client calls.

## Limits worth knowing

- The whole import runs in one database transaction. A very large file means a
  long transaction and the whole document in memory. Untested at scale.
- Figma has no endpoint that lists a user's teams -- nine candidates all 404 --
  so `PENPOT_FIGMA_TEAM_ID` is set by an admin, comma separated for several.
- Drafts belong to no team folder and are invisible to the API. Those files
  need the exporter plugin directly.
