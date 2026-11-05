# Third-party notices

Torrente is MIT licensed (see `LICENSE`). Parts of its GLSL are adapted from the MIT-licensed work below; the
notices are kept as the MIT license requires. No Complementary Shaders code is used: the "Unbound" style is a
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
