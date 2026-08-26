#!/usr/bin/env python3
"""
Genera la fuente de iconos EMPAQUETADA a partir de la completa, dejando solo los glifos que la
app usa de verdad.

**Por qué existe.** `material_symbols_rounded.ttf` trae 4174 iconos y pesa 14,9 MB — el 87 % del
APK — de los que Siku usa poco más de un centenar. Y el coste no es solo de descarga: Android
construye un `Typeface` por cada combinación de ejes (FILL/wght/GRAD/opsz) PARSEANDO el archivo
entero, así que en frío el arranque pagaba dos `measure` de 89,8 y 41,4 ms solo por resolver la
fuente (medido con Perfetto). El precalentamiento de `MaterialSymbolFont.preload` esconde esa
factura moviéndola a un hilo de fondo; el subset la elimina.

**Cómo decide qué conservar.** La lista NO se escribe a mano: se cruzan dos conjuntos.

  1. Los nombres de ligadura que la fuente completa ofrece (4174).
  2. Todo literal de string `"[A-Za-z0-9_]{2,40}"` que aparezca en el código Kotlin.

La intersección es lo que se conserva. Que el barrido de literales sea AMPLIO es deliberado y es
lo que hace el script seguro: un icono cuyo nombre esté escrito en cualquier parte del código
entra, venga de una llamada directa, de un `when` o de una tabla de constantes. Los falsos
positivos (un `"search"` que era una ruta) cuestan un glifo y no rompen nada; un falso NEGATIVO
sería un icono invisible en producción, así que el sesgo va a propósito hacia incluir de más.

Lo único que este método no puede ver es un nombre COMPUESTO en tiempo de ejecución
("ic_" + tipo). No los hay, y la tarea `verifyIconFontSubset` de `app/build.gradle.kts` vuelve a
cruzar código y manifiesto en cada release para que tampoco los haya mañana.

**Uso** (necesita `pip install fonttools`):

    python tools/subset_icon_font.py

Lee  tools/fonts/material_symbols_rounded_full.ttf
Deja app/src/main/assets/fonts/material_symbols_rounded.ttf   (la que se empaqueta)
     tools/icon_subset_manifest.txt                           (los nombres, para la verificación)

Las dos salidas se COMMITEAN: el build no corre este script (exigiría Python y fonttools a
cualquiera que compile), solo verifica que el manifiesto cubre lo que el código pide.
"""

import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FULL_FONT = os.path.join(ROOT, "tools", "fonts", "material_symbols_rounded_full.ttf")
OUT_FONT = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "material_symbols_rounded.ttf")
MANIFEST = os.path.join(ROOT, "tools", "icon_subset_manifest.txt")
SOURCES = os.path.join(ROOT, "app", "src", "main", "java")

# Los nombres se escriben como literal de string y se resuelven por LIGADURA (ver MaterialSymbol.kt).
LITERAL = re.compile(r'"([A-Za-z0-9_]{2,40})"')


def ligature_names(font):
    """{nombre en minúsculas: glifo} de cada ligadura de la fuente.

    La fuente mapea mayúscula y minúscula al MISMO glifo, así que el nombre reconstruido desde el
    cmap sale en mayúsculas; se normaliza a minúsculas, que es como se escribe en el código.
    """
    reverse = {}
    for codepoint, glyph in font.getBestCmap().items():
        reverse.setdefault(glyph, chr(codepoint))

    def unwrap(subtable):
        while subtable.__class__.__name__ == "ExtensionSubst":
            subtable = subtable.ExtSubTable
        return subtable

    names = {}
    for lookup in font["GSUB"].table.LookupList.Lookup:
        for subtable in lookup.SubTable:
            subtable = unwrap(subtable)
            if subtable.__class__.__name__ != "LigatureSubst":
                continue
            for first, ligatures in subtable.ligatures.items():
                for ligature in ligatures:
                    components = [first] + list(ligature.Component)
                    if all(c in reverse for c in components):
                        text = "".join(reverse[c] for c in components).lower()
                        names[text] = ligature.LigGlyph
    return names


def icons_used_in_sources():
    """Todo literal del código que PODRÍA ser un nombre de icono (se filtra luego contra la fuente)."""
    found = set()
    for folder, _, files in os.walk(SOURCES):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(folder, name)
            text = io.open(path, encoding="utf-8", errors="replace").read()
            found.update(match.group(1).lower() for match in LITERAL.finditer(text))
    return found


def main():
    try:
        from fontTools.ttLib import TTFont
    except ImportError:
        sys.exit("Falta fonttools: pip install fonttools")

    if not os.path.exists(FULL_FONT):
        sys.exit("No está la fuente completa en %s" % FULL_FONT)

    font = TTFont(FULL_FONT)
    available = ligature_names(font)
    used = sorted(icons_used_in_sources() & set(available))
    if not used:
        sys.exit("No se encontró ningún icono en el código: algo va mal, no se toca la fuente.")

    # Glifos a conservar: el resultado de cada ligadura MÁS los caracteres que la componen (sin
    # ellos la ligadura no se puede formar, porque el texto de entrada son esas letras).
    keep = {available[name] for name in used}
    cmap = font.getBestCmap()
    for name in used:
        for char in name:
            glyph = cmap.get(ord(char))
            if glyph:
                keep.add(glyph)

    # `--no-layout-closure` es lo que hace que esto sirva de algo: por defecto pyftsubset añade
    # todo glifo ALCANZABLE por GSUB desde los de entrada, y como cada icono se forma con las
    # mismas ~30 letras, el cierre arrastraría los 4174 de vuelta.
    command = [
        sys.executable, "-m", "fontTools.subset", FULL_FONT,
        "--output-file=%s" % OUT_FONT,
        "--glyphs=%s" % ",".join(sorted(keep)),
        "--layout-features=rlig,rclt",
        "--no-layout-closure",
        # Los ejes son el motivo de usar esta fuente (FILL anima, wght acompaña al peso del texto).
        "--recalc-bounds",
        "--drop-tables+=meta",
        "--name-IDs=*",
    ]
    subprocess.run(command, check=True)

    io.open(MANIFEST, "w", encoding="utf-8", newline="\n").write("\n".join(used) + "\n")

    before = os.path.getsize(FULL_FONT)
    after = os.path.getsize(OUT_FONT)
    print("iconos conservados: %d de %d" % (len(used), len(available)))
    print("%.1f MB -> %.2f MB (%.1f %% menos)" % (
        before / 1e6, after / 1e6, 100 * (1 - after / before)))


if __name__ == "__main__":
    main()
