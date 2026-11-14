// Keep UI glyphs in the Lucide/Folio catalogue, including state variants.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../app/src/main');
const source = fs.readFileSync(path.join(root, 'java/com/folio/notes/ui/FolioIcons.kt'), 'utf8');
const names = new Set([...source.matchAll(/val (\w+) (?:by icon|get\(\))/g)].map(m => m[1]));
const glyphs = new Map([...source.matchAll(/val (\w+) by icon\("([^"]*)"(?:, "([^"]*)")?/g)].map(m => [m[1], [m[2], m[3] || '']]));
assert(names.size > 150, 'Catalogue was not fully read');
for (const [name, [line, solid]] of glyphs) assert(line || solid, `${name} is empty`);
assert.match(source, /defaultWidth = 24\.dp, defaultHeight = 24\.dp/);
assert.match(source, /viewportWidth = 24f, viewportHeight = 24f/);
assert.match(source, /strokeLineWidth = 2f/);
assert.match(source, /strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round/);
assert(!/addGroup|asAndroidPath/.test(source), 'Do not resize individual Lucide glyphs');
assert(fs.readFileSync(path.join(root, 'assets/licenses/lucide.txt'), 'utf8').includes('Cole Bemis'), 'Missing upstream license');
for (const name of ['Back', 'Forward', 'Undo', 'Redo', 'ChevronLeft', 'ChevronRight', 'Return']) {
    assert.match(source, new RegExp(`val ${name} by icon\\([^\\n]+mirror = true`), `${name} lost RTL mirroring`);
}
for (const name of ['Star', 'Bookmark']) {
    const [outline] = glyphs.get(name);
    assert.deepEqual(glyphs.get(`${name}Filled`), [outline, outline], `${name} selection changes its contour`);
}
function scan(dir) {
    for (const file of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, file.name);
        if (file.isDirectory()) scan(full);
        else if (/\.(kt|xml)$/.test(file.name)) {
            const text = fs.readFileSync(full, 'utf8');
            assert(!/androidx\.compose\.material\.icons|android\.R\.drawable|@android:drawable/.test(text), `Stock icon in ${full}`);
            for (const [, name] of text.matchAll(/FolioIcons\.(\w+)/g)) {
                assert(names.has(name), `Missing glyph ${name} in ${full}`);
            }
        }
    }
}
scan(root);
console.log(`Lucide/Folio icons: ${glyphs.size} glyphs, references, style, RTL and selected contours checked.`);
