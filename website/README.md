# dudan website

The site at [dudan.bvdm.ai](https://dudan.bvdm.ai): a home page and a get-started page, both static, with no build step and no framework.

| Path | Contents |
| --- | --- |
| `public/` | What gets deployed: `index.html`, `style.css`, `script.js`, fonts and images |
| `public/get-started/index.html` | The get-started page at `/get-started/`. It follows [`skills/README.md`](../skills/README.md) and the `dudan-hermes` skill, so update it when those change |
| `og/og.html` | Source for `public/og-image.png`, the image link previews show |
| `tools/generate.mjs` | Rebuilds the generated files in `public/` from the repository |
| `netlify.toml` | Publish directory, security headers and caching |

## Run it locally

```sh
python -m http.server 4321 --bind 127.0.0.1 --directory website/public
```

Then open http://127.0.0.1:4321/.

## Regenerate assets

Run this after changing a screenshot in `docs/screenshots/`, the brand artwork in `assets/`, the app's fonts or `og/og.html`:

```sh
npm ci --prefix website/tools
npm run --prefix website/tools generate
```

The script writes:

- WebP copies and crops of the screenshots in `public/assets/screens/`
- The app's Google Sans Flex and Google Sans Code fonts, cut down to Latin characters, with their OFL licenses
- The wordmark and animated reveal without their dark backdrop, the favicons and the download QR code
- The Phosphor icon sprite between the `icons:start` and `icons:end` markers in `index.html` and `get-started/index.html`
- `og-image.png`, rendered with headless Chrome (set `CHROME` if it isn't in a standard location)

## How the page behaves

The download buttons read the latest GitHub release through the GitHub API. On an Android phone, they link straight to the APK. On other devices, they scroll to the setup section, which has a QR code for the phone. If the API call fails, the links fall back to the releases page.

The "Watch the video" button in the hero opens the intro video in a dialog. The video is hosted on Mux. Its player loads from `player.mux.com` only when someone opens the dialog, with Mux Data tracking turned off. Without JavaScript, the button links to Mux's player page.

The nav has no background over the hero, so it shows the sky. When the page scrolls, `script.js` adds `is-scrolled` and the nav turns into a blurred night bar. On the get-started page, the prompts get a copy button.

Nothing else on the pages calls an outside service. There are no analytics, cookies or external fonts.

## Deploy

The site is hosted on Netlify. DNS for `bvdm.ai` is on Cloudflare, with a CNAME from `dudan.bvdm.ai` to the Netlify site. The record is DNS-only (not proxied), so Netlify issues the TLS certificate.

Netlify publishes `main` from GitHub. Every push to `main` deploys the site, with `website` as the base directory, so a change goes live by merging it into `main`.

Don't run `netlify deploy --prod` from a local checkout. It publishes whatever that working tree holds, and the next push to `main` replaces it. That's how unmerged work went live on 30 September and then disappeared. To show someone a change before it's merged, make a draft deploy instead. It gets its own URL and leaves the live site alone:

```sh
cd website
netlify deploy
```
