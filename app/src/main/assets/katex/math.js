'use strict';
// Only this local shell executes code. Source arrives through JSON, never HTML.
window.folioResult = null;
let activeRequest = 0;
window.renderMath = async function (request) {
    const generation = ++activeRequest;
    window.folioResult = null;
    const target = document.getElementById('formula');
    target.style.fontSize = request.fontSize + 'px';
    target.style.color = request.color;
    target.className = request.blocks ? 'document' : '';
    target.style.width = request.blocks ? request.width + 'px' : '';
    target.style.lineHeight = request.blocks ? String(request.lineHeight / request.fontSize) : '';
    target.replaceChildren();
    if (request.blocks) {
        appendBlocks(target, request.blocks, request.color);
    } else {
        renderFormula(target, request.latex, request.displayMode, request.color);
    }
    // Even syntactically valid empty/phantom formulas must not silently lose source.
    if (!target.textContent.trim() && !target.querySelector('img.diagram')) target.textContent = request.latex || ' ';
    // Force font requests from the new DOM before awaiting their completion.
    target.getBoundingClientRect();
    await document.fonts.ready;
    // Diagrams size themselves from the SVG viewBox once decoded; a broken one is dropped, not left blank.
    await Promise.all(Array.from(target.querySelectorAll('img.diagram'), (img) => img.decode().catch(() => img.remove())));
    // A cancelled native caller may already have returned this renderer to the pool.
    if (generation !== activeRequest) return;
    if (request.blocks) fitMath(target, Math.max(1, request.width - 4));
    const bounds = target.getBoundingClientRect();
    window.folioResult = {
        id: request.id,
        width: Math.ceil(bounds.width),
        height: Math.ceil(bounds.height),
        rendered: !!target.querySelector('.katex, img.diagram'),
        error: !!target.querySelector('.katex-error'),
        text: target.textContent
    };
};


function renderFormula(target, latex, displayMode, color) {
    try {
        katex.render(latex, target, {
            displayMode: !!displayMode, throwOnError: false, strict: false, trust: false,
            maxExpand: 1000, maxSize: 50, errorColor: color
        });
    } catch (_) {
        target.textContent = latex;
    }
    if (!target.textContent.trim()) target.textContent = latex || ' ';
}

function mathNode(latex, display, color) {
    const outer = document.createElement('span');
    outer.className = display ? 'math display-math' : 'math';
    const inner = document.createElement('span');
    inner.className = 'math-content';
    renderFormula(inner, latex, display, color);
    outer.appendChild(inner);
    return outer;
}

function appendInlines(parent, items, color) {
    for (const item of items) {
        if (item.type === 'break') parent.appendChild(document.createElement('br'));
        else if (item.type === 'math') parent.appendChild(mathNode(item.latex, item.display, color));
        else {
            const run = document.createElement(item.code ? 'code' : 'span');
            run.textContent = item.text;
            if (item.bold) run.style.fontWeight = '600';
            if (item.italic) run.style.fontStyle = 'italic';
            if (item.strike) run.style.textDecoration = 'line-through';
            parent.appendChild(run);
        }
    }
}

function appendBlocks(target, blocks, color) {
    for (const block of blocks) {
        if (block.type === 'math') {
            target.appendChild(mathNode(block.latex, true, color));
            continue;
        }
        if (block.type === 'svg') {
            // An <img> never runs scripts or loads anything external, unlike inline SVG.
            const img = document.createElement('img');
            img.className = 'diagram';
            img.alt = 'Diagram';
            img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(block.markup);
            target.appendChild(img);
            continue;
        }
        const tag = { paragraph: 'div', heading: 'h' + Math.min(3, Math.max(1, block.level)),
            quote: 'blockquote', code: 'pre', bullets: 'ul', numbers: 'ol', divider: 'hr' }[block.type] || 'div';
        const node = document.createElement(tag);
        if (block.type === 'paragraph') node.className = 'paragraph';
        if (block.type === 'code') node.textContent = block.text;
        else if (block.items) {
            for (const item of block.items) {
                const li = document.createElement('li');
                appendInlines(li, item, color);
                node.appendChild(li);
            }
        } else if (block.inlines) appendInlines(node, block.inlines, color);
        target.appendChild(node);
    }
}

function fitMath(target, availableWidth) {
    // Scale only equations too wide for their paragraph. Natural-height inline fractions expand
    // the line; they never compete with a fixed Compose placeholder or disappear through ellipsis.
    for (const outer of target.querySelectorAll('.math')) {
        const inner = outer.firstElementChild;
        const bounds = inner.getBoundingClientRect();
        const containerWidth = Math.min(availableWidth, outer.parentElement.clientWidth || availableWidth);
        const scale = Math.min(1, containerWidth / Math.max(1, bounds.width));
        if (scale < 1) {
            inner.style.transform = 'scale(' + scale + ')';
            inner.style.marginRight = bounds.width * (scale - 1) + 'px';
            outer.style.height = bounds.height * scale + 'px';
            if (!outer.classList.contains('display-math')) outer.style.width = bounds.width * scale + 'px';
            // A scaled inline-block still reserves its original height unless its line box is reset.
            outer.style.lineHeight = '0';
        }
    }
}
