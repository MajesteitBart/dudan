# Brand assets

[Back to dudan](../README.md) · [Documentation](README.md)

The four supplied SVGs in `assets/` are the source artwork. Keep them intact when generating platform assets.

| Asset | Use |
| --- | --- |
| [Icon](../assets/dudan-icon.svg) | Standalone mark on a dark background |
| [Wordmark](../assets/dudan-wordmark.svg) | Full name, used at the top of the README |
| [App icon](../assets/dudan-app-icon.svg) | Rounded tile with the mark and colored rim |
| [Animated reveal](../assets/dudan-reveal.svg) | Logo animation; open the downloaded SVG in a browser to play it |

The three static designs also have PNG exports alongside their SVGs. The reveal stays as SVG to preserve its animation.

## Regenerate Android assets and PNGs

From the repository root:

```sh
npm ci --prefix tools/brand-assets
npm run --prefix tools/brand-assets generate
```

The generator renders the supplied paths and gradients with resvg. It writes the static PNG exports, transparent in-app marks with and without a glow, and the adaptive launcher foreground. Android supplies the launcher's outer mask, so the foreground omits the artwork's rounded tile and rim. The launcher background uses the tile's original colors.

Notification and themed launcher icons use a white silhouette of the same mark. Their path coordinates are in the generator; update those too if the source geometry changes. The launcher background is in `app/src/main/res/drawable/ic_launcher_background.xml`.

`DudanMark` displays the transparent mark throughout the app. It pulses while the agent works and stays still when idle.
