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
    try {
        katex.render(request.latex, target, {
            displayMode: request.displayMode,
            throwOnError: false,
            strict: false,
            trust: false,
            maxExpand: 1000,
            maxSize: 50,
            errorColor: request.color
        });
    } catch (_) {
        target.textContent = request.latex;
    }
    // Even syntactically valid empty/phantom formulas must not silently lose source.
    if (!target.textContent.trim()) target.textContent = request.latex || ' ';
    // Force font requests from the new DOM before awaiting their completion.
    target.getBoundingClientRect();
    await document.fonts.ready;
    // A cancelled native caller may already have returned this renderer to the pool.
    if (generation !== activeRequest) return;
    const bounds = target.getBoundingClientRect();
    window.folioResult = {
        id: request.id,
        width: Math.ceil(bounds.width),
        height: Math.ceil(bounds.height),
        rendered: !!target.querySelector('.katex'),
        error: !!target.querySelector('.katex-error'),
        text: target.textContent
    };
};
