# Themes

Open **Settings → Appearance** on the watch, or **Appearance** in the companion, to choose a theme. **Royal Signal** (purple/lime) is the default; **Phosphor** and **Amber Terminal** are included. Each device remembers its own selection. Selecting a different theme recreates the screen so native widgets, dialogs and artwork update together. Accepted server work and connection/voice preferences are preserved. Wake listening resumes through the normal activity lifecycle.

## Add a skin

All visual tokens and the theme catalog live in `shared/src/main/res/values/themes.xml`. Both Android modules include these resources. Add a style inheriting `HermesTheme`, override the semantic tokens, then add its stable ID, display name and style reference to the three parallel catalog arrays in the same order. The first catalog entry is the fallback for missing or removed IDs. Never rename an existing ID just to change its display name.

| Tokens | Purpose |
| --- | --- |
| `hwBackground`, `hwSurface`, `hwSurfaceRaised` | Screens, dialogs and secondary controls |
| `hwAccent`, `hwOnAccent` | Primary actions, waveform and readable action text |
| `hwTextPrimary`, `hwTextSecondary` | Main content and supporting labels |
| `hwOutline`, `hwPressed`, `hwWarning` | Bezel outlines, pressed controls and attention state |
| `hwArtworkBackground`, `hwArtworkPanel` | GPU and fallback artwork colors |
| `hwCornerRadius`, `hwFontFamily` | Rounded action controls and interface typography |

```xml
<style name="MySkin" parent="HermesTheme">
    <item name="hwAccent">#80E5FF</item>
    <item name="hwOnAccent">#061B24</item>
    <item name="hwArtworkPanel">#12435A</item>
</style>
```

Other tokens inherit from the base skin. Override all relevant surfaces and text colors for a complete palette. Keep text contrast at least 4.5:1. The theme tests check main text, secondary text and action text, render round-watch Settings for each catalog entry, compile the shader, verify older-API fallback colors and check persistent selection.

`ThemeStore` owns catalog lookup and the separate `hermes_appearance` preference. `ThemeTokens` resolves resources for custom drawing and controls. Native widgets use Android theme attributes bound to those same tokens. The AGSL renderer receives palette uniforms once at construction; no shader source edits are needed for recoloring. The Canvas fallback and bezel controls consume the same tokens.

Themes change appearance, not request processing, voice providers, permissions or interaction geometry. This version uses packaged Android resources, not downloaded executable skins. Changing the waveform's geometry or animation still requires renderer work.
