// Injected into jpdb.io at document start by the app. Tells the app when a vocab card is
// graded on the website itself, so the offline queue drops its copy of that card.
(function () {
    'use strict';
    if (window.__jpdbAppBridge) return;
    window.__jpdbAppBridge = true;

    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (!(form instanceof HTMLFormElement) || !window.jpdbApp) return;
        var data;
        try { data = new FormData(form, e.submitter || undefined); } catch (_) { data = new FormData(form); }
        var c = data.get('c'), g = data.get('g');
        if (typeof c === 'string' && /^vf,\d+,\d+$/.test(c) && g) {
            window.jpdbApp.postMessage(JSON.stringify({ type: 'reviewed', c: c, g: String(g) }));
        }
    }, true);
})();
