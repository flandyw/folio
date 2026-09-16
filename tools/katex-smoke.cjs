#!/usr/bin/env node
// No npm install or network required: test the exact distributable shipped in the APK.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../app/src/main/assets/katex');
const katex = require(path.join(root, 'katex.min.js'));
const formulas = [
  String.raw`\Pr(X \le 3)`, String.raw`\Pr(A \mid B)`, String.raw`\Pr(X=x)`, String.raw`\Pr(X \geq 4)`,
  String.raw`\Pr(X \le 3)=\sum_{x=0}^{3}\binom{n}{x}p^x(1-p)^{n-x}`,
  String.raw`\frac{dy}{dx}`, String.raw`\sqrt{x^2+1}`, String.raw`\binom{n}{r}`,
  String.raw`\int_a^b f(x)\,dx`, String.raw`\sum_{i=1}^{n} x_i`,
  String.raw`\begin{pmatrix}a & b \\ c & d\end{pmatrix}`,
  String.raw`f(x)=\begin{cases}x^2 & x \ge 0 \\ -x & x < 0\end{cases}`,
  String.raw`\left|\frac{x-1}{x+2}\right|`, String.raw`\alpha+\beta+\theta+\sin x+\log x`,
];
for (const latex of formulas) for (const displayMode of [false, true]) {
  const html = katex.renderToString(latex, { displayMode, throwOnError: true, strict: false, trust: false });
  assert.match(html, /class="katex"/);
  if (latex.includes(String.raw`\Pr`)) assert.match(html, />Pr<\/span>/);
}
const malformed = katex.renderToString(String.raw`\frac{`, { throwOnError: false });
assert.match(malformed, /katex-error/);
assert.match(malformed, /\\frac\{/);
const hostile = katex.renderToString(String.raw`\href{https://example.com}{click}`, { throwOnError: false, trust: false, strict: false });
assert.doesNotMatch(hostile, /<a\b|<img\b/);
const css = fs.readFileSync(path.join(root, 'katex.min.css'), 'utf8');
const fonts = [...css.matchAll(/url\(([^)]+)\)/g)].map(m => m[1].replace(/["']/g, ''));
assert.ok(fonts.length > 0);
for (const font of fonts) {
  assert.match(font, /^fonts\/KaTeX_[\w-]+\.(woff2|woff|ttf)$/);
  assert.ok(fs.statSync(path.join(root, font)).size > 0, font);
}
assert.ok(fs.readFileSync(path.join(root, 'LICENSE'), 'utf8').includes('MIT'));
assert.ok(fs.readFileSync(path.join(root, 'VERSION.txt'), 'utf8').includes(katex.version));
console.log(`KaTeX ${katex.version}: ${formulas.length * 2} representative renders, malformed/untrusted input, ${fonts.length} local font URLs passed.`);
