#!/usr/bin/env bash
# Downloads the decorative fonts used by "write on photos/videos" into app/src/main/assets/fonts.
# All fonts are from Google Fonts (github.com/google/fonts) under the SIL Open Font License or Apache 2.0.
# Missing fonts are skipped: the app only lists the fonts that are actually bundled.
set -u
OUT="$(dirname "$0")/../app/src/main/assets/fonts"
mkdir -p "$OUT/licenses"
BASE="https://raw.githubusercontent.com/google/fonts/main"
ok=0; miss=0

# fetch <id> <dir> <file candidates...>
fetch() {
  local id="$1" dir="$2"; shift 2
  for file in "$@"; do
    if curl -fsSL --retry 2 -o "$OUT/$id.ttf" "$BASE/$dir/$file"; then
      curl -fsSL -o "$OUT/licenses/$id-OFL.txt" "$BASE/$dir/OFL.txt" 2>/dev/null \
        || curl -fsSL -o "$OUT/licenses/$id-LICENSE.txt" "$BASE/$dir/LICENSE.txt" 2>/dev/null || true
      echo "✓ $id ($file)"; ok=$((ok+1)); return
    fi
  done
  rm -f "$OUT/$id.ttf"; echo "✗ $id (not found)"; miss=$((miss+1))
}

# Arabic
fetch arefruqaa        ofl/arefruqaa        ArefRuqaa-Bold.ttf ArefRuqaa-Regular.ttf
fetch arefruqaaink     ofl/arefruqaaink     ArefRuqaaInk-Bold.ttf ArefRuqaaInk-Regular.ttf
fetch amiri            ofl/amiri            Amiri-Bold.ttf Amiri-Regular.ttf
fetch reemkufi         ofl/reemkufi         'ReemKufi%5Bwght%5D.ttf' ReemKufi-Regular.ttf ReemKufi-Bold.ttf
fetch reemkufifun      ofl/reemkufifun      'ReemKufiFun%5Bwght%5D.ttf' ReemKufiFun-Regular.ttf
fetch kufam            ofl/kufam            'Kufam%5Bwght%5D.ttf' Kufam-Regular.ttf
fetch notonastaliq     ofl/notonastaliqurdu 'NotoNastaliqUrdu%5Bwght%5D.ttf' NotoNastaliqUrdu-Regular.ttf
fetch lateef           ofl/lateef           Lateef-Bold.ttf Lateef-Regular.ttf
fetch alkalami         ofl/alkalami         Alkalami-Regular.ttf
fetch vibes            ofl/vibes            Vibes-Regular.ttf
fetch blakaink         ofl/blakaink         BlakaInk-Regular.ttf
fetch katibeh          ofl/katibeh          Katibeh-Regular.ttf
fetch rakkas           ofl/rakkas           Rakkas-Regular.ttf
fetch lalezar          ofl/lalezar          Lalezar-Regular.ttf
fetch jomhuria         ofl/jomhuria         Jomhuria-Regular.ttf
fetch marhey           ofl/marhey           'Marhey%5Bwght%5D.ttf' Marhey-Regular.ttf
fetch elmessiri        ofl/elmessiri        'ElMessiri%5Bwght%5D.ttf' ElMessiri-Bold.ttf ElMessiri-Regular.ttf
fetch cairo            ofl/cairo            'Cairo%5Bslnt,wght%5D.ttf' 'Cairo%5Bwght%5D.ttf' Cairo-Bold.ttf
fetch tajawal          ofl/tajawal          Tajawal-Bold.ttf Tajawal-Regular.ttf
fetch changa           ofl/changa           'Changa%5Bwght%5D.ttf' Changa-Regular.ttf
fetch mirza            ofl/mirza            Mirza-Bold.ttf Mirza-Regular.ttf
# English
fetch greatvibes       ofl/greatvibes       GreatVibes-Regular.ttf
fetch alexbrush        ofl/alexbrush        AlexBrush-Regular.ttf
fetch pinyonscript     ofl/pinyonscript     PinyonScript-Regular.ttf
fetch sacramento       ofl/sacramento       Sacramento-Regular.ttf
fetch dancingscript    ofl/dancingscript    'DancingScript%5Bwght%5D.ttf' DancingScript-Regular.ttf
fetch pacifico         ofl/pacifico         Pacifico-Regular.ttf
fetch satisfy          apache/satisfy       Satisfy-Regular.ttf
fetch kaushanscript    ofl/kaushanscript    KaushanScript-Regular.ttf
fetch lobster          ofl/lobster          Lobster-Regular.ttf
fetch caveat           ofl/caveat           'Caveat%5Bwght%5D.ttf' Caveat-Regular.ttf
fetch permanentmarker  apache/permanentmarker PermanentMarker-Regular.ttf
fetch neonderthaw      ofl/neonderthaw      Neonderthaw-Regular.ttf
fetch tiltneon         ofl/tiltneon         'TiltNeon%5BXROT,YROT%5D.ttf' TiltNeon-Regular.ttf
fetch monoton          ofl/monoton          Monoton-Regular.ttf
fetch bungeeshade      ofl/bungeeshade      BungeeShade-Regular.ttf
fetch cinzeldecorative ofl/cinzeldecorative CinzelDecorative-Bold.ttf CinzelDecorative-Regular.ttf
fetch playfairdisplay  ofl/playfairdisplay  'PlayfairDisplay%5Bwght%5D.ttf' PlayfairDisplay-Regular.ttf
fetch bebasneue        ofl/bebasneue        BebasNeue-Regular.ttf
fetch righteous        ofl/righteous        Righteous-Regular.ttf
fetch audiowide        ofl/audiowide        Audiowide-Regular.ttf
fetch orbitron         ofl/orbitron         'Orbitron%5Bwght%5D.ttf' Orbitron-Regular.ttf

echo "fonts: $ok bundled, $miss missing"
