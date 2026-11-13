(() => {
    const failStartup = (message, error) => {
        console.error(message, error || "");
        globalThis.__haiveStartupError = error ? `${message} ${error}` : message;
        const splash = document.getElementById("haiveSplash");
        if (splash) {
            splash.setAttribute("aria-label", "The Aive failed to start");
            splash.dataset.startupError = globalThis.__haiveStartupError;
        }
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
        globalThis.__haiveBrowserLocale = locale;
        return locale;
    };

    const loadHaive = () => new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = "haive.js";
        script.onload = resolve;
        script.onerror = () => reject(new Error("Could not load haive.js."));
        document.body.appendChild(script);
    });

    normalizeBrowserLocale();
    loadHaive()
        .then(() => {
            globalThis.__haiveAppScriptLoaded = true;
        })
        .catch((error) => {
            failStartup("The Aive could not load.", error);
        });
})();
