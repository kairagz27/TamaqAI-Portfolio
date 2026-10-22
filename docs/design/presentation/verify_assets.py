"""Audit bundled glyphs, palette contrast and complete locale resources."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[3]
res = ROOT / 'app/src/main/res'
source = (ROOT / 'app/src/main/java/com/tamaqai/ui/theme/TamaqTheme.kt').read_text(encoding='utf-8')

def contrast(a, b):
    def luminance(value):
        channels = [int(value[i:i+2], 16) / 255 for i in (0, 2, 4)]
        linear = [v / 12.92 if v <= .04045 else ((v+.055)/1.055)**2.4 for v in channels]
        return sum(v*w for v, w in zip(linear, (.2126, .7152, .0722)))
    high, low = sorted((luminance(a), luminance(b)), reverse=True)
    return (high+.05)/(low+.05)

# The fixed palette intentionally ignores wallpaper-derived dynamic color.
assert 'dynamicLightColorScheme(' not in source and 'dynamicDarkColorScheme(' not in source
assert 'surfaceTint=Color.Transparent' in source
assert 'primary=p.AccentBrown' in source

tokens_source = (ROOT / 'app/src/main/java/com/tamaqai/ui/theme/WellnessTokens.kt').read_text(encoding='utf-8')
opacities = {key: float(value) for key, value in re.findall(
    r'const val (\w+Opacity) = ([.\d]+)f', tokens_source)}


def composite(tint, background, alpha):
    """sRGB over, matching the fixed non-HDR app / camera-window color space."""
    return ''.join(f'{round(int(tint[i:i+2], 16)*alpha + int(background[i:i+2], 16)*(1-alpha)):02X}'
                   for i in (0, 2, 4))


palettes = {}
for name in ('Light', 'Dark'):
    block = re.search(r'val '+name+r'Palette=WellnessPalette\((.*?)\n\)', source, re.S).group(1)
    colors = dict(re.findall(r'(\w+)=Color\(0xFF([0-9A-F]{6})\)', block))
    surfaces = ('PageBackground', 'CardSurface', 'SecondarySurface', 'SelectedSurface', 'DisabledSurface')
    pairs = [(foreground, background, 4.5) for foreground in ('TextPrimary', 'TextSecondary')
             for background in surfaces]
    pairs += [(foreground, background, 4.5) for foreground in
              ('AccentBrown', 'ErrorColor', 'WarningColor', 'SuccessColor', 'ProteinColor', 'FatColor', 'CarbColor')
              for background in ('PageBackground', 'CardSurface')]
    pairs += [('ControlOutline', 'CardSurface', 3), ('ErrorColor', 'ErrorSurface', 4.5)]
    ratios = {f'{f}/{b}': round(contrast(colors[f], colors[b]), 3) for f, b, _ in pairs}
    for f, b, minimum in pairs:
        assert contrast(colors[f], colors[b]) >= minimum, (name, f, b, ratios[f'{f}/{b}'])
    for role in (*surfaces, 'BorderSoft', 'ControlOutline', 'ChartTrack',
                 'AccentBrown', 'Caramel', 'Apricot', 'Pistachio', 'ProteinPastel'):
        r, g, b = (int(colors[role][i:i+2], 16) for i in (0, 2, 4))
        assert r >= g >= b, (name, role, 'surface / accent acquired a cool or olive tint')
    label = colors['PageBackground'] if name == 'Dark' else colors['CardSurface']
    button = contrast(colors['AccentBrown'], label)
    assert button >= 4.5
    assert contrast(colors['Apricot'], '332A24') >= 4.5
    glass_ratios = {}
    for material in ('card', 'navigation', 'panel', 'field'):
        token = ('dark'+material[0].upper()+material[1:] if name == 'Dark' else material)+'Opacity'
        alpha = opacities[token]
        assert 0 < alpha < 1, (token, 'glass must remain translucent')
        # Cards use the shared warm page source. Navigation / dense panels /
        # fields also pass worst-case black / white photograph samples because
        # navigation can sample visible meal photos from the shared Content layer.
        backgrounds = [colors[role] for role in ('PageBackground', 'CardSurface', 'SecondarySurface')]
        if material in ('navigation', 'panel', 'field'):
            backgrounds += ['000000', 'FFFFFF']
        material_ratios = []
        for background in backgrounds:
            filled = composite(colors['CardSurface'], background, alpha)
            for foreground in ('TextPrimary', 'TextSecondary'):
                value = contrast(colors[foreground], filled)
                assert value >= 4.5, (name, material, foreground, background, round(value, 3))
                material_ratios.append(value)
        glass_ratios[material] = {'opacity': alpha, 'minimum_text_contrast': round(min(material_ratios), 3),
                                 'photo_extremes_checked': material in ('navigation', 'panel', 'field')}
    palettes[name] = {'ratios': ratios, 'primary_button': round(button, 3),
                      'glass_composites': glass_ratios}

characters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
cyrillic = ''.join(chr(n) for n in range(0x410, 0x450))+'ЁёӘәҒғҚқҢңӨөҰұҮүҺһІі'
fonts = {}
for name in ('inter', 'lora', 'noto_sans', 'bodoni_moda'):
    path = res / 'font' / (name+'.ttf')
    font = TTFont(path)
    cmap = font.getBestCmap()
    required = characters + ('' if name == 'bodoni_moda' else cyrillic)
    assert all(ord(char) in cmap for char in required), (name, 'missing required glyph')
    weights = [axis for axis in font['fvar'].axes if axis.axisTag == 'wght']
    assert weights and weights[0].minValue <= 400 <= weights[0].maxValue
    if name != 'bodoni_moda': assert weights[0].maxValue >= 600
    fonts[name] = {'required_glyphs_present': True, 'cyrillic_required': name != 'bodoni_moda',
                   'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                   'weight_range': [weights[0].minValue, weights[0].maxValue]}

catalogs = {}
for directory in ('values', 'values-ru', 'values-kk'):
    strings = {}; plurals = {}
    for file in (res / directory).glob('*.xml'):
        root = ET.parse(file).getroot()
        for node in root.findall('string'):
            if node.get('translatable') == 'false': continue
            assert node.get('name') not in strings, ('duplicate', directory, node.get('name'))
            strings[node.get('name')] = ''.join(node.itertext())
        for node in root.findall('plurals'):
            plurals[node.get('name')] = {item.get('quantity'): item.text for item in node}
    catalogs[directory] = (strings, plurals)
baseline = catalogs['values'][0]
for directory, (strings, plurals) in catalogs.items():
    assert strings.keys() == baseline.keys(), (directory, 'missing/extra strings')
    for key, value in strings.items():
        assert sorted(re.findall(r'%(?:\d+\$)?[dsf]', value)) == sorted(re.findall(r'%(?:\d+\$)?[dsf]', baseline[key])), (directory, key)
    assert 'meal_count' in plurals and 'other' in plurals['meal_count']
assert {'one', 'few', 'many', 'other'} <= catalogs['values-ru'][1]['meal_count'].keys()
report = {'palettes': palettes, 'fonts': fonts, 'locale_strings': len(baseline),
          'complete_locales': ['en', 'ru', 'kk'], 'russian_plural_forms': True,
          'fixed_warm_palette': True, 'material_surface_tint_disabled': True}
target = Path(__file__).with_name('assets-audit.json')
target.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'palette_contrast': True, 'font_glyphs': True, 'complete_locales': True, 'strings': len(baseline)}))
