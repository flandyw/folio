// Keep UI glyphs in the custom catalogue, including state variants.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../app/src/main');
const source = fs.readFileSync(path.join(root, 'java/com/folio/notes/ui/FolioIcons.kt'), 'utf8');
const names = new Set([...source.matchAll(/val (\w+) (?:by icon|get\(\))/g)].map(m => m[1]));
const glyphs = new Map([...source.matchAll(/val (\w+) by icon\("([^"]*)"(?:, "([^"]*)")?/g)].map(m => [m[1], [m[2], m[3] || '']]));
assert(names.size > 150, 'Catalogue was not fully read');
for (const [name, [line, solid]] of glyphs) assert(line || solid, `${name} is empty`);
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
console.log(`Custom icons: ${glyphs.size} glyphs, all references and selected contours checked.`);
