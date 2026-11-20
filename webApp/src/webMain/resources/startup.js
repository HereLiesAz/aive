(() => {
    const failStartup = (message, error) => {
        console.error(message, error || "");
        globalThis.__aiveStartupError = error ? `${message} ${error}` : message;
        // With no splash to carry it, a failed start says so in the page itself.
        const notice = document.createElement("p");
        notice.setAttribute("role", "alert");
        notice.textContent = "The Aive failed to start. " + globalThis.__aiveStartupError;
        notice.style.cssText = "font-family:sans-serif;color:#F2E8D9;background:#1E1A17;margin:0;padding:16px;white-space:pre-wrap";
        document.body.appendChild(notice);
    };

    const normalizeBrowserLocale = () => {
        const candidates = [
            navigator.language,
            ...(Array.isArray(navigator.languages) ? navigator.languages : []),
            document.documentElement.lang,
            "en-US",
        ];
        let locale = "en-US";
        for (const candidate of candidates) {
            if (typeof candidate !== "string" || !candidate.trim()) continue;
            try {
                const normalized = candidate.trim().replaceAll("_", "-");
                const canonical = Intl.getCanonicalLocales(normalized)[0];
                if (canonical) {
                    locale = canonical;
                    break;
                }
            } catch (_) {
                // Keep trying until a valid BCP-47 locale is found.
            }
        }

        document.documentElement.lang = locale;
        try {
            Object.defineProperty(navigator, "language", {
                configurable: true,
                get: () => locale,
            });
            Object.defineProperty(navigator, "languages", {
                configurable: true,
                get: () => [locale],
            });
        } catch (_) {
            // Some browsers expose non-configurable Navigator properties. The document
            // language remains a valid fallback for Compose in that case.
        }
        globalThis.__aiveBrowserLocale = locale;
        return locale;
    };

    const loadAive = () => new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = "aive.js";
        script.onload = resolve;
        script.onerror = () => reject(new Error("Could not load aive.js."));
        document.body.appendChild(script);
    });

    normalizeBrowserLocale();
    loadAive()
        .then(() => {
            globalThis.__aiveAppScriptLoaded = true;
        })
        .catch((error) => {
            failStartup("The Aive could not load.", error);
        });
})();
