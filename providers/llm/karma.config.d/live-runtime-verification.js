// Opt-in live acceptance for JS and Wasm browser tests (ProviderNeutralLiveRuntimeVerificationTest).
//
// A browser has no process environment, so the live-verification settings are copied from the
// Gradle/Node environment into the Karma client configuration, where the test reads them from
// `__karma__.config.aiveLiveEnv`. Nothing is copied unless AIVE_LIVE_RUNTIME_VERIFICATION=1, so
// ordinary browser test runs are unchanged and the live test skips itself.
(function () {
    const env = process.env;
    if (env.AIVE_LIVE_RUNTIME_VERIFICATION !== "1") return;

    const providerKeys = ["OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY", "XAI_API_KEY"];
    const liveEnv = {};
    Object.keys(env)
        .filter((name) => name.startsWith("AIVE_LIVE_") || providerKeys.includes(name))
        .forEach((name) => { liveEnv[name] = env[name]; });

    // A live run waits on a real provider for minutes; the defaults would abort it after seconds.
    const liveTimeoutMillis = 30 * 60 * 1000;
    config.client = config.client || {};
    config.client.aiveLiveEnv = liveEnv;
    config.client.mocha = Object.assign({}, config.client.mocha, { timeout: liveTimeoutMillis });
    config.browserNoActivityTimeout = liveTimeoutMillis;
    config.browserDisconnectTimeout = liveTimeoutMillis;

    // Where outbound HTTPS must go through a proxy, the headless browser has to be told explicitly.
    const proxy = env.HTTPS_PROXY || env.https_proxy;
    if (proxy && Array.isArray(config.browsers)) {
        config.customLaunchers = config.customLaunchers || {};
        config.browsers = config.browsers.map((browser) => {
            const name = browser + "LiveProxy";
            config.customLaunchers[name] = {
                base: browser,
                flags: ["--proxy-server=" + proxy],
            };
            return name;
        });
    }
})();
