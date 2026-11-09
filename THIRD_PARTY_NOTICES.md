# Third-party notices

Torrente is licensed under the GNU General Public License v3.0 only (see `LICENSE` and `NOTICE`). Parts of its GLSL
and styles are adapted from the MIT-licensed work below; the notices are kept as the MIT license requires. MIT is
compatible with GPL-3.0, and the MIT terms continue to apply to that material. No Complementary Shaders code is used: the "Unbound" style is a
look-alike built from standard techniques (Fresnel-Schlick, Beer-Lambert, sums of sines).

## Glimmer Shaders v1.5.2 (water absorption and scattering coefficients)
Used in `assets/torrente/styles/glimmer.json` (absorption) and the Glimmer style colour.

    MIT License

    Copyright (c) 2024 Josh Britain (jbritain)

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

## "Very fast procedural ocean" by afl_ext (wave normal octaves)
The detail wave normals in `water/WaterGlsl.java` (`torrente_ripple`) follow the octave scheme of this shader, which
Glimmer's `lib/water/waveNormals.glsl` also uses: https://www.shadertoy.com/view/MdXyzX. Torrente computes the
slope analytically in one pass instead of by finite differences. Licensed MIT by its author (afl_ext, 2017).

## Aurora Fantasy 5.6 (water colour, absorption, reflection and fog values)
Used in `assets/torrente/styles/aurora.json` (the "Aurora" style). Only numeric values are taken from its water
shading (`lib/water_aurora.glsl`, `lib/water_waves.glsl`, `lib/color_utils.glsl`, `lib/config.glsl`): per-block
extinction, the body-colour floor, the reflection index, the underwater fog colour and the mid-depth water colour. No
shader code and no textures are copied.

    Aurora Fantasy, modified by in2bubble (https://github.com/in2bubble/Aurora-Fantasy), based on MakeUp Ultra Fast
    by Javier Garduno (KDXavier, https://github.com/javiergcim), with additional work credited to Entokito.
    Licensed under the GNU Lesser General Public License, version 3 or (at your option) any later version
    (https://www.gnu.org/licenses/lgpl-3.0.txt). The LGPL-3.0 is the GPL-3.0 (Torrente's `LICENSE`) plus additional
    permissions, so the material may be used in this GPL-3.0 work.
