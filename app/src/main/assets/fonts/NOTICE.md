# Bundled fonts

The font files in this directory are third-party works redistributed under their own licenses,
independent of the GPL-3.0 that covers Siku Music itself. Both licenses below permit
redistribution and embedding in an application, including commercially, provided the license
text travels with the font — which is the purpose of this file.

## Google Sans Flex

- `GoogleSansFlex.ttf`
- Copyright © Google LLC
- **SIL Open Font License 1.1** — <https://openfontlicense.org/>
- Source: <https://fonts.google.com/specimen/Google+Sans+Flex>

Released as open source by Google in November 2025. The OFL permits use, modification and
redistribution, embedded or bundled; the only restriction is that the font itself (original or
modified) may not be sold on its own.

## Roboto Flex / Roboto Italic

- `RobotoFlex.ttf`, `Roboto-Italic-VariableFont_wdth,wght.ttf`
- Copyright © Google LLC
- **Apache License 2.0** — <https://www.apache.org/licenses/LICENSE-2.0>
- Source: <https://fonts.google.com/specimen/Roboto+Flex>

## Material Symbols Rounded

- `material_symbols_rounded.ttf`
- Copyright © Google LLC
- **Apache License 2.0** — <https://www.apache.org/licenses/LICENSE-2.0>
- Source: <https://github.com/google/material-design-icons>

**Subset.** The bundled file is a subset of the upstream font, reduced to the icons this app
actually draws (117 of 4174, 0.24 MB instead of 14.9 MB). Nothing else was altered: the outlines,
the variable axes and the ligature names are the originals. The Apache License 2.0 permits
modification and redistribution; this note is the record of the change. The unmodified font is
kept in `tools/fonts/` and `tools/subset_icon_font.py` regenerates the subset from it.

---

Neither Google nor the font authors endorse this project. Apart from the Material Symbols subset
described above, the fonts are unmodified.
