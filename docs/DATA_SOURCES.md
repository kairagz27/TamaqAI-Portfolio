# Data sources and nutrition provenance

Checked for publication on **2026-10-22**. The source facts were retrieved and
qualified on 2026-10-20; this audit does not pretend to recheck physical labels.

## Public starter dataset

`backend/catalog_seed/openfoodfacts-kz.json` contains **39** community records
whose source country tags include Kazakhstan. Nutrients, barcode, exact variant,
per-100g/per-100ml basis, source URL, date and namespace are retained. These tags
are evidence from the source, not an independent survey of current shop stock.
The set is small and uneven; it is not nationwide coverage or a claim of label
accuracy. [Source qualification evidence](catalog/openfoodfacts-evidence-20261020.json).

The source's numeric qualifiers are respected: a record with values such as
“greater than” was excluded instead of converted into exact quantities. No
density is invented to convert grams to millilitres. Unknown nutrient values
remain null; partial totals are explicitly marked.

## License and attribution

Open Food Facts' [official API documentation](https://openfoodfacts.github.io/openfoodfacts-server/api/)
separates the **ODbL database**, **DbCL individual contents**, and separately
licensed **CC BY-SA 3.0 images**. Its [official reuse guidance](https://openfoodfacts.github.io/openfoodfacts-server/api/tutorials/license-be-on-the-legal-side/)
explains attribution and share-alike for a derived redistributed database.
API access also requires an identifying User-Agent and rate-limit compliance. Full terms:
[ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/),
[DbCL 1.0](https://opendatacommons.org/licenses/dbcl/1-0/).

**Contains information from Open Food Facts, made available under the Open
Database License (ODbL).** This attribution applies to the seed and its evidence
export. They are separate from the MIT application code. No source photographs,
contributors' profiles or label artwork are included. A future image import
needs its own image-license/attribution review; the database license is not an
image license or a trademark license.

## Sources not redistributed

Earlier private branches included FoodMaster, Sultan, Rakhat and two Coffee Boom
menu entries. A publicly readable manufacturer's page is not evidence of a
redistribution license. Those seed files and the copied official facts table
are excluded from this export. Existing
server versions and diary snapshots are not deleted or altered. Obtain explicit
permission or establish a valid reuse basis before reintroducing the data.
These files and old Git objects are not included in the separate portfolio export.
The original private repository is not rewritten. See the [export audit](export/README.md)
for the precise included-file boundary and review.

Restaurant selection is implemented, but this public seed includes no licensed
venue menu. Coffee Boom did not provide verified portion masses and complete
nutrition in the prior research. There is no replacement using typical recipes.
The public demo can show the honest empty venue state. Its missing-nutrition
test uses an original synthetic dish, not a restaurant's name or recipe.

## Values shown to the user

| Origin | What it means |
|---|---|
| Photo analysis | Model estimate, possibly wrong composition/mass; user review required |
| OFF catalogue | Published community fields for the selected source version; check the label |
| Personal correction | User-entered values, private to that owner |
| Portfolio fake | Deterministic synthetic output; the photograph is not analysed |

An entry stores the selected version, basis, quantity and calculated nutrition
as a diary snapshot. Reimporting a catalogue updates the current version without
recalculating confirmed meals. Component mass and consumed fraction are separate
inputs, recalculated from their original reference to avoid cumulative rounding.
The demo performs no online OFF lookup and no paid model call.
