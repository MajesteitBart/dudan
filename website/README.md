# dudan website

The site at [dudan.bvdm.ai](https://dudan.bvdm.ai): one static page, with no build step and no framework.

| Path | Contents |
| --- | --- |
| `public/` | What gets deployed: `index.html`, `style.css`, `script.js`, fonts and images |
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
- The Phosphor icon sprite between the `icons:start` and `icons:end` markers in `index.html`
- `og-image.png`, rendered with headless Chrome (set `CHROME` if it isn't in a standard location)

## How the page behaves

The download buttons read the latest GitHub release through the GitHub API. On an Android phone, they link straight to the APK. On other devices, they scroll to the setup section, which has a QR code for the phone. If the API call fails, the links fall back to the releases page.

Nothing else on the page calls an outside service. There are no analytics, cookies or external fonts.

## Deploy

The site is hosted on Netlify. DNS for `bvdm.ai` is on Cloudflare, with a CNAME from `dudan.bvdm.ai` to the Netlify site. The record is DNS-only (not proxied), so Netlify issues the TLS certificate.

```sh
cd website
netlify deploy --prod
```
