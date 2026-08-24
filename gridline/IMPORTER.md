# Figma importer — design

## What this replaces

The picker lists Figma files and then asks you to open each one and run the
Penpot Exporter plugin by hand. This reads the file over Figma's REST API and
writes a Gridline file directly. No plugin, no zip, no per-file clicking.

## Why this is now feasible

Earlier I argued against it, on the grounds that the exporter plugin's
converters are written against Figma's *plugin* API and would have to be
rewritten. That part is still true. What changed is the other half: Penpot's
own **file builder** lives in this repo at
`common/src/app/common/files/builder.cljc`, and is what `@penpot/library`
(the package the exporter plugin uses) is compiled from.

So we do not need to construct `.penpot` archives by hand. We drive the same
builder the official tooling drives, from Clojure, and persist the result with
`app.binfile.common/save-file!` — the function binfile import itself uses.

    Figma REST JSON  ->  app.common.files.builder  ->  bfc/save-file!

## Where it runs

Backend, not browser:

- the file JSON for a real document is large; a browser tab is the wrong place
- images must be fetched and stored server-side anyway
- no CORS considerations, and it can become a background task later

## Mapping

| Figma | Gridline |
|---|---|
| DOCUMENT | file |
| CANVAS | page |
| FRAME / COMPONENT / INSTANCE | board (`add-board`) |
| GROUP | group (`add-group`) |
| RECTANGLE | `:rect` |
| ELLIPSE | `:circle` |
| TEXT | `:text` with a root/paragraph-set/paragraph/text content tree |
| LINE | `:rect` with zero height |
| VECTOR, STAR, POLYGON, BOOLEAN_OPERATION | `:rect` placeholder, reported |

Geometry comes from `absoluteBoundingBox`. Both Figma and Penpot position
children in absolute page coordinates, so no transform maths is needed for a
flat conversion — parenting is expressed by `frame-id`, which the builder
maintains through its parent stack.

Paints: SOLID becomes a fill with `:fill-color` and `:fill-opacity`. Figma
colours are 0–1 floats per channel; Penpot wants hex plus a separate opacity.
Gradients and images are not converted yet and are reported.

## Converted

Geometry from `absoluteTransform` and the node's own size -- never
`absoluteBoundingBox`, which is the post-rotation envelope. Rotation via the
plugin's `applyInverseRotation`, storing Penpot's unrotated reference point
plus both transform matrices.

Fills and strokes (both reversed, as Figma stacks them the other way round),
stroke alignment and dash state, corner radii as `r1..r4`, blend modes, drop
and inner shadows, layer blur, opacity, locked.

Text with its actual font family, the `gfont-<slug>` id, weight, size, style,
line height as a ratio, letter spacing, case, decoration and alignment.

Vector geometry via `?geometry=paths`, parsed by Penpot's own SVG path parser.
Stroke-only nodes fill their stroke outline, which is what makes line art
arrive as drawn.

Images, resolved through `/v1/files/:key/images` and stored as file media.

Constraints, and auto-layout as Penpot flex -- including the reversed flex
direction, which is not a bug: Penpot orders flex children opposite to Figma,
so the direction is inverted to compensate, exactly as the plugin does.

## Still not converted

Components as real components (instances flatten to boards), grid layout,
gradients, vector networks beyond flattened path geometry, prototyping.

Each unsupported node is counted and returned to the caller, so the result
says what did not come across rather than failing silently — the honest
failure mode for a converter that is knowingly partial.

## Scope required

`file_content:read`, in addition to `folders:read`. Both must be ticked on
the Figma OAuth app.
