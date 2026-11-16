(() => {
    const app = document.getElementById("webApp");
    const splash = document.getElementById("aiveSplash");
    if (!app || !splash) return;

    const dismissSplash = () => {
        splash.classList.add("aive-splash-ready");
        window.setTimeout(() => splash.remove(), 220);
    };

    if (app.childNodes.length > 0) {
        dismissSplash();
        return;
    }

    const observer = new MutationObserver(() => {
        if (app.childNodes.length === 0) return;
        observer.disconnect();
        requestAnimationFrame(dismissSplash);
    });
    observer.observe(app, { childList: true });
})();
