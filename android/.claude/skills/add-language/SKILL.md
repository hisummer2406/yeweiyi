---
name: add-language
description: Add a new UI language (locale) to 安卓液位仪 (com.sansen.yeweiyi), or refresh/finish an already-added non-Chinese/English language. Reads and updates the master workbook — the online Tencent Docs sheet (https://docs.qq.com/sheet/DS2trbXdua0JPRWF1, via the tencent-docs MCP; not a downloaded .xlsx) — 文案对照 + 数组文案对照 sheets, regenerates values-<lang>/strings.xml and array.xml, wires the language switcher (R.array.language + SetBaseFragment.LANGUAGE_CODES), and surfaces the 3 locale-specific images (sansen_logo/sw_true/sw_false) that need real design assets, not translation. Use whenever the user asks to add support for a new language, resume adding one that's partly done, or update an existing added language's translations.
metadata:
  author: session-2026-09-i18n-spanish
  version: "1.1"
---

# Adding a language to 安卓液位仪

This skill exists because Spanish (`es`) took a very long, expensive session to get right — multiple rounds of reachability auditing, two rounds of "you marked the wrong thing as unreachable," a missed resource *type* (`array.xml`, not just `strings.xml`), and two rounds of "the Excel highlighting convention is wrong." Every language after Spanish should be cheap by comparison, because the expensive part — figuring out *which* of the ~600 raw string keys and 17 arrays are actually reachable — is **already done and does not need to be redone.**

## The workbook lives online — read and write it through the tencent-docs MCP (since 2026-10-18)

The master workbook is the Tencent Docs sheet **https://docs.qq.com/sheet/DS2trbXdua0JPRWF1** (`file_id` = `DS2trbXdua0JPRWF1`). The user maintains it there and shares it with translators; local `.xlsx` files under `temp/` are stale downloads — **never read them as the source, never edit them**. If the user gives a different link, use that one (the `file_id` is the path segment after `/sheet/`).

- Load tools with `ToolSearch` (`select:mcp__tencent-docs__sheet_get_sheet_info,mcp__tencent-docs__sheet_get_cell_data,mcp__tencent-docs__sheet_set_range_value,mcp__tencent-docs__sheet_set_cell_style,mcp__tencent-docs__sheet_insert_dimension`). If the MCP isn't connected, stop and ask the user to connect it — don't fall back to a local file.
- `sheet_get_sheet_info` → sheet ids (as of 2026-10-18: `000001` 说明, `000002` 文案对照, `000004` 数组文案对照, `000003` 已确认不可达, `000005` 数组-已确认不可达 — re-check, don't hardcode). Read with `sheet_get_cell_data` + `return_csv: true`; rows/cols are 0-based, row 0 is the header.
- Locate rows by the Key / 数组名+下标 columns of a fresh read, never by row numbers remembered from an earlier read or a local file — rows get inserted.
- Write with `sheet_set_range_value` (batch), insert rows with `sheet_insert_dimension`, then **read the range back** to confirm.
- "标红" (flagging a cell the user should look at — anything you changed or AI-filled): `sheet_set_cell_style` with `bg_color: FFF8CBAD`, `font_color: FFC00000`, `bold: true`, on that language's cells only (see Step 3).

**Known defects in translator-supplied cells — the code's XML wins:** format specifiers come back mangled (`%1 $ d`, `%1 dd s`, `%1 d−raqamlid−raqamli s`, `%1$d` turned into `%1$s`), and cells carry stray trailing `\n` or leading tabs. When generating `values-<lang>/strings.xml`, restore the placeholders to match `values/strings.xml` (same indices and types — a wrong type crashes `String.format` at runtime) and trim whitespace; then write the corrected text back into the online cell and mark it red, so the sheet and the code agree.

## Scope: what this skill does NOT need to do

**Do not re-run the reachability audit.** `app/src/main/res/values/strings.xml` and `array.xml` carry `[不可达-YYYY-MM-DD]` / `[已屏蔽-YYYY-MM-DD]` comments on every key/array confirmed dead, and the corresponding Java call sites are already commented out. Reachability is a property of the *code path*, not the language — a key unreachable for Spanish is unreachable for every future language too. The master workbook's four sheets are the standing record of that audit:

- **文案对照** (currently 205 rows) — genuinely live string keys. Translate these.
- **数组文案对照** (currently 53 rows, 9 arrays) — genuinely live `<string-array>` items. Translate these.
- **已确认不可达** / **数组-已确认不可达** — confirmed-dead keys/arrays, kept only as a decision record. **Never add a translation column here.** If you're ever tempted to "just translate everything for completeness," don't — that's the exact mistake this workbook exists to prevent.

The only time you *do* need to touch the audit is if the codebase has changed since the workbook was last generated (new screens, new strings added by other work on the branch) — see "Detecting drift" below. That's a much smaller, incremental check, not a from-scratch audit.

## Step 0 — gather inputs before touching anything

Ask the user (don't guess):
1. **Language name and ISO 639-1 code** (e.g., French → `fr`). The code becomes the `values-<code>/` resource qualifier and the `SetBaseFragment.LANGUAGE_CODES` entry.
2. **Translation source.** In order of preference, matching how Spanish was actually sourced:
   - A design-draft export (Blue Lake / PSD) for this language — if one exists, re-extracting real UI copy from it beats AI translation every time. See `temp/Android_strings/README.md` and `PSD重新提取_核对报告.md` for the extraction methodology used for Spanish (`psd-tools`, board-relative coordinate matching, majority-vote dedup) if a new PSD needs the same treatment.
   - A user-provided batch of already-translated text (pasted directly, or a file) — apply verbatim, no AI involved, mark reviewed/trusted.
   - No source at all → AI-translate every row, but every single one must be flagged for human review (see "Status and highlighting" below). Never silently AI-fill without flagging — that produced three real bugs this session (`hour_unit`, `tank_density`, `tank_empty_capacity` all got marked "trusted" by mistake; ask the user for the `add-language` retrospective in this project's memory if you want the details).
3. Confirm the workbook link — default is the online sheet above; ask only if the user mentions a new/different document.

## Step 1 — read the workbook, find where you are

Read both live sheets' headers from the online workbook (see "The workbook lives online" above). As of the Spanish pass they were (as of 2026-10-18 `乌兹别克斯坦语 | 俄语` columns follow `状态` without their own status columns — flag their AI/corrected cells by red highlighting instead):

- `文案对照`: `Key | 所在页面 | 通俗页面说明 | 中文 | 英文 | 西班牙语 | 状态`
- `数组文案对照`: `数组名 (string-array) | 下标 | 所在页面/消费者 | 通俗说明 | 中文 | 英文 | 西班牙语 | 状态`

**Column-naming rule for a second-and-later language:** the single trailing `状态` column only ever describes *one* language's review state, which stops working the moment there's more than one translated language. The first time this skill runs for a language after Spanish:

1. Rename the existing `状态` header to `西班牙语状态` (retroactively disambiguating it — the data doesn't move, just the header text).
2. Insert two new columns immediately after it (or after the last language pair if a third+ language is being added): `<语言名>` then `<语言名>状态`.

So after adding French, the header becomes: `... | 西班牙语 | 西班牙语状态 | 法语 | 法语状态`. Keep each language's translation column and its status column adjacent — that pairing is what makes the highlighting rule in Step 3 possible to apply correctly, and it's what a copywriter scanning the sheet expects.

Do this same column-insert on **both** `文案对照` and `数组文案对照` independently — their row sets and row counts are different (205 vs 53), don't try to share column positions by row-number arithmetic between the two sheets.

## Step 2 — detecting drift before you translate

Before trusting the workbook's row list as complete, do a cheap sanity check, not a full re-audit:

```bash
# counts should match what the workbook's 说明 sheet claims for "真正会显示给用户"
grep -c '<string name=' app/src/main/res/values/strings.xml   # minus the [不可达] ones
```

If the live counts have visibly drifted (a lot more or fewer `<string>`/`<string-array>` entries than the workbook expects, or `git log` shows string-resource-touching commits since the workbook's `生成时间`), tell the user before proceeding — new keys since the last audit need the same reachability check this session did (see `CLAUDE.md`'s "Known landmines" section, specifically the "A listener is set ≠ the callback actually runs" entry, for the methodology and the list of traps already found). Don't silently either skip new keys or blindly translate everything without checking.

## Step 3 — fill in translations, mark status honestly

For every row in `文案对照` and `数组文案对照`:

- Fill the new language column with the translation (from the design draft, the user's batch, or AI).
- Fill the new status column: use the **same two values already established** — `已翻译` (verified: design-draft-sourced, or the user directly supplied/confirmed it) and `AI待review` (anything you generated yourself, even a one-word "obviously correct" translation, even something that reuses another already-verified key's wording). **The status is per-cell honesty about provenance, not a confidence guess.** If you can't point to where the translation came from — a specific design-draft file, or the user's own words — it's `AI待review`, full stop.
- **Reused translations still need their own provenance check.** When a row's source language text exactly matches another row that's already verified in a *different* target language (e.g., French `guan_type`'s "卧罐/立罐" matches the pattern already verified for Spanish `createtable_horizontal_tank`/`vertical_tank`), reusing the *established wording pattern* is fine, but it's still a fresh translation into a new language — mark it `AI待review` unless the design draft or the user separately confirmed the French wording specifically. Don't inherit Spanish's `已翻译` status by association.
- Cross-check any row where the English column looks suspicious before trusting it as your source (this project's `values-en/array.xml` `oiltypeArray` has two entries that don't match the Chinese original — see the comment block at the top of `values-es/array.xml` for the specifics). Translate from **Chinese** (`中文` column), the authoritative source, not from English, when the two disagree — and leave a note if you find a new instance of this.

### Status and highlighting — the part that took two rounds to get right

Do **not** invent your own highlighting scheme. Copy the *exact* style — not just the fill color — from the existing Spanish column's cells, which is the one now-verified-correct reference:

```python
# find a real 已翻译 row and a real AI待review row in 文案对照's 西班牙语 column (col F)
# for each, dump cell.font (name, size, bold, color.rgb) and cell.fill (fgColor.rgb) —
# the "AI待review" style is NOT just a red fill: it's fill=F8CBAD *and* font.bold=True,
# font.color.rgb='00C00000' (dark red text on top of the red fill). Missing the font half
# is exactly the bug that shipped for a full round of this session before being caught.
```

Apply that combined style **only to the new language's own translation column**, for rows whose new-language status is `AI待review`. Every other column in that row — Key, 所在页面, 中文, 英文, the *other* languages' columns and their statuses — stays plain/white, even if some other language's cell in that same row happens to also be under review. Highlighting is per-language-column, never per-row. (`文案对照` and `数组文案对照` were both wrongly whole-row-red at one point this session; if you ever see a row where non-translation columns are colored, that's the bug recurring — fix it the same way.)

If you need to flag something about a cell *without* putting it in the translation value itself (e.g., "this English source text doesn't match Chinese, heads up") — mark the cell red and tell the user in chat (or ask them to add a comment in Tencent Docs), never text concatenated into a value column. Concatenating a warning into a value corrupts data a copywriter or a script might read literally, and it happened once already this session (`oiltypeArray`'s English column had literal warning text glued onto "Sulfuric Acid").

## Step 4 — generate values-\<lang\>/strings.xml and array.xml

- `strings.xml`: one `<string name="KEY">translation</string>` per **non-empty** cell in the new language column of `文案对照`. A row with no translation (source had nothing reliable and the user didn't ask for full AI-fill coverage) is simply omitted — Android falls back to `values/strings.xml` (Chinese) for that key, which is the established, deliberate behavior (see the header comment in `values-es/strings.xml` for the reasoning), not a bug to "fix" by force-filling.
- `array.xml`: one `<string-array name="ARRAY"><item>...</item>...</string-array>` per array that has **any** translated items in `数组文案对照`. **An array is all-or-nothing** — Android does not merge `<string-array>` item-by-item across locales, it substitutes the whole array. So either translate every item of a given array or translate none of them; a partially-translated array silently displays a mix of the new language and whatever locale that array falls back to, which is worse than not translating it at all. If the user only wants to translate some items, tell them this constraint before they invest partial effort.
- **Item-count parity is mandatory, not a nice-to-have.** For every array you write, the item count must exactly equal `values/array.xml`'s count for that same array name:

```python
import xml.etree.ElementTree as ET
def counts(path):
    return {a.get('name'): len(a.findall('item')) for a in ET.parse(path).getroot().findall('string-array')}
zh, new = counts('app/src/main/res/values/array.xml'), counts(f'app/src/main/res/values-{lang}/array.xml')
assert all(zh.get(k) == v for k, v in new.items()), "count mismatch — Android will index into the wrong item at runtime"
```

- Ignore `values-zh/` — it's a legacy, partially-maintained duplicate of the unqualified `values/` (which is already Chinese, unqualified = default). It's not the canonical Chinese source and is out of scope for this skill; don't update it and don't treat its presence as meaning anything.

## Step 5 — wire the language switcher

Adding resource files alone does not make the new language selectable. Two more things need the new language, in lockstep, at the **same list position** in both:

1. **`R.array.language`** — append the new language's native-script name as a new `<item>` to `values/array.xml`'s `language` string-array (e.g., `Français`), **and** append that same array's translation into *every other* locale's `language` array too, translated into that locale (so the picker shows correct names regardless of current UI language) — `values-en/array.xml` gets "French", `values-es/array.xml` gets "Francés", and the new `values-fr/array.xml` needs the full list translated into French itself ("Chino"/"Inglés"/"Español" → "Chinois"/"Anglais"/"Espagnol", plus "Français" for itself).
2. **`SetBaseFragment.LANGUAGE_CODES`** (`app/src/main/java/com/sansen/yeweiyi/fragment/SetBaseFragment.java`) — append the ISO code (`"fr"`) to the array, at the same index as the new `R.array.language` item. The comment right above the array explicitly says the two must stay in lockstep; update the comment's language list too.

`LanguageUtil.java` and `DataUtils.getOilType()` are already generic (the latter was fixed earlier this session specifically so a non-zh/non-en locale falls back safely instead of NPE-ing) — no changes needed there for a new language, but it's worth a quick read of `DataUtils.java`'s `getOilType()` comment to confirm that's still true before assuming it.

## Step 6 — the 3 images with baked-in text (`sansen_logo`/`sw_true`/`sw_false`)

These PNGs carry text in the pixels (the logo's tagline, the switch's on/off labels), so they can't be translated as strings. **Layout since 2026-10-18 (user decision):** the default `drawable-mdpi/` holds the **English** artwork, Chinese lives in `drawable-zh-mdpi/`, and Spanish keeps its own `drawable-es-mdpi/sw_true.png`/`sw_false.png` (its logo was identical to English, so it falls back to the default). Every other language — `en`, `ru`, `uz` and any future one — falls back to the English default automatically. So a new language needs **no** image directory: don't create `drawable-<lang>-mdpi/` unless the user supplies real localized artwork for it. If they do, compare dimensions with `drawable-mdpi/` (`sansen_logo.png` ~650×50–90px, `sw_true.png`/`sw_false.png` 52×25) and flag a mismatch. Never fake localized artwork by editing another locale's PNG.

## Step 7 — verify

Every round of changes this session ended with the same four commands; run them for real, don't assume:

```bash
python3 -c "import xml.etree.ElementTree as ET; ET.parse('app/src/main/res/values-<lang>/strings.xml')"
python3 -c "import xml.etree.ElementTree as ET; ET.parse('app/src/main/res/values-<lang>/array.xml')"
./gradlew :app:processDebugResources
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
# then actually read app/build/test-results/testDebugUnitTest/*.xml or grep the tests= line —
# UP-TO-DATE prints nothing and looks identical to a real pass
```

## Step 8 — close the loop on the workbook

- Update the `说明` sheet: bump whatever counts changed, add a dated note describing what was added and its source (mirroring the existing `2026-09-24 更正.../全量核对...` rows — this workbook's changelog lives in that sheet, not in commit messages alone).
- Verify fill/font consistency across the *entire* new column with a script (not spot checks) before calling it done — see Step 3's highlighting rule.
- Do not `git commit` until the user has verified the result — this project's standing rule, not specific to this skill.
