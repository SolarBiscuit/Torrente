# Third-party notices

Torrente is licensed under the GNU General Public License v3.0 only (see `LICENSE` and `NOTICE`). Parts of its GLSL
and styles are adapted from the MIT-licensed work below; the notices are kept as the MIT license requires. MIT is
compatible with GPL-3.0, and the MIT terms continue to apply to that material. No Complementary Shaders code is used: the "Atmosphera" style
(named "Unbound" before 0.5.0) is a look-alike built from standard techniques (Fresnel-Schlick, Beer-Lambert, sums of sines).
Torrente 0.5.0 removed the Glimmer style, so no Glimmer Shaders values remain; the "Lagoon" style that replaced it is
original.

## "Very fast procedural ocean" by afl_ext (wave normal octaves)
The detail wave normals in `water/WaterGlsl.java` (`torrente_ripple`) follow the octave scheme of this shader, which
Glimmer's `lib/water/waveNormals.glsl` also uses: https://www.shadertoy.com/view/MdXyzX. Torrente computes the
slope analytically in one pass instead of by finite differences. Licensed MIT by its author (afl_ext, 2017).

## Aurora Fantasy 5.6 (water colour, absorption, reflection and fog values)
Used in `assets/torrente/styles/celestial.json` (the "Celestial" style, named "Aurora" before 0.5.0). Only numeric values are taken from its water
shading (`lib/water_aurora.glsl`, `lib/water_waves.glsl`, `lib/color_utils.glsl`, `lib/config.glsl`): per-block
extinction, the body-colour floor, the reflection index, the underwater fog colour and the mid-depth water colour. No
shader code and no textures are copied.

    Aurora Fantasy, modified by in2bubble (https://github.com/in2bubble/Aurora-Fantasy), based on MakeUp Ultra Fast
    by Javier Garduno (KDXavier, https://github.com/javiergcim), with additional work credited to Entokito.
    Licensed under the GNU Lesser General Public License, version 3 or (at your option) any later version
    (https://www.gnu.org/licenses/lgpl-3.0.txt). The LGPL-3.0 is the GPL-3.0 (Torrente's `LICENSE`) plus additional
    permissions, so the material may be used in this GPL-3.0 work.
