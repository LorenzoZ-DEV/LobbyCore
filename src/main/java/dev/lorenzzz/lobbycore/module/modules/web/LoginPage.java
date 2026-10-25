package dev.lorenzzz.lobbycore.module.modules.web;

/**
 * The page a browser gets instead of a bare 401.
 *
 * <p>Self-contained and separate from the real front end on purpose: it is served <em>before</em>
 * authentication, so it must not pull in {@code app.js} or anything else the session protects,
 * and it has to keep working when the rest of the panel does not. The only thing it shares with
 * the panel is the branding, which is why the images are served ahead of the auth check.
 */
final class LoginPage {

    private LoginPage() {}

    static String html(WebSettings settings) {
        String background = settings.background();
        String logo = settings.logo();

        // The screenshot is a backdrop, not the subject: it sits under a heavy scrim so the
        // form stays the thing you look at and the text stays readable over any art an admin
        // drops in.
        String backdrop = background.isEmpty() ? "" : """
                <div class="shot" style="background-image:\
                linear-gradient(#12101ad9,#12101af2),url('%s')"></div>""".formatted(escape(background));

        String badge = logo.isEmpty()
                ? "<div class=\"mark\"></div>"
                : "<img class=\"logo\" src=\"" + escape(logo) + "\" alt=\"\">";

        return """
                <!DOCTYPE html><html lang="it"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>LobbyCore &mdash; Accedi</title><style>
                :root{color-scheme:dark;--accent:%s}
                *{box-sizing:border-box;margin:0}
                body{min-height:100vh;display:grid;place-items:center;padding:24px;
                background:#12101a;color:#eee9f6;position:relative;overflow:hidden;
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;font-size:14px}
                .shot{position:fixed;inset:0;background-size:cover;background-position:center;z-index:0}
                form{position:relative;z-index:1;background:#1b1626e8;border:1px solid #2f2740;
                border-radius:18px;padding:30px;width:min(94vw,380px);
                box-shadow:0 22px 60px #000000a6;backdrop-filter:blur(6px);text-align:center}
                .logo{width:84px;height:84px;object-fit:contain;margin:0 auto 14px;display:block;
                filter:drop-shadow(0 6px 18px #0009)}
                .mark{width:34px;height:34px;border-radius:11px;background:var(--accent);margin:0 auto 14px}
                h1{font-size:19px;margin-bottom:6px;letter-spacing:-.01em;
                font-family:ui-monospace,'Cascadia Mono',Consolas,monospace}
                p{color:#9a92ab;margin-bottom:20px;line-height:1.5;font-size:13px}
                label{display:block;text-align:left;font-size:11px;letter-spacing:.14em;
                text-transform:uppercase;color:#9a92ab;margin-bottom:7px;
                font-family:ui-monospace,Consolas,monospace}
                input{width:100%%;padding:11px 13px;border-radius:9px;border:1px solid #372e4a;
                background:#141020;color:#eee9f6;font-family:ui-monospace,Consolas,monospace;font-size:13px}
                input:focus{outline:2px solid var(--accent);outline-offset:1px;border-color:transparent}
                button{width:100%%;margin-top:16px;padding:11px;border:0;border-radius:9px;
                background:var(--accent);color:#140f1c;font-weight:700;font-size:14px;cursor:pointer}
                button:hover{filter:brightness(1.08)}
                small{display:block;margin-top:16px;color:#6f677f;line-height:1.5;font-size:12px}
                code{font-family:ui-monospace,Consolas,monospace;color:#b9a6e0}
                </style></head><body>
                %s
                <form method="GET" action="/">
                  %s
                  <h1>LobbyCore</h1>
                  <p>Serve il token del pannello. Lo trovi in <code>modules/web.yml</code>,
                     oppure generane uno valido pochi minuti con <code>/lobbycore panel</code>.</p>
                  <label for="token">Token</label>
                  <input id="token" name="token" type="password" autocomplete="off" autofocus>
                  <button type="submit">Entra</button>
                  <small>La sessione resta attiva in questo browser finche' non esci.</small>
                </form></body></html>
                """.formatted(escape(settings.accent()), backdrop, badge);
    }

    /**
     * Minimal HTML escaping for the handful of config values spliced into the page.
     *
     * <p>These come from web.yml, so whoever can set them can already do worse — but a stray
     * quote in a path would silently break the attribute it sits in, and that is worth
     * preventing on its own.
     */
    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
