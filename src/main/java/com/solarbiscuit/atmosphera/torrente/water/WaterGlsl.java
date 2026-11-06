package com.solarbiscuit.atmosphera.torrente.water;

/**
 * Torrente's GLSL, inserted into Xenon's terrain program by AtmospheraLib. Every Torrente terrain feature registers
 * the shared declarations; their {@code #ifndef} guards make sure they are emitted once, in both stages, whichever
 * features are active (so the varyings always link). All names start with {@code torrente}.
 * <p>
 * Frame slots (see {@link WaterFeatures}): {@code torrente_Color} (rgb water colour, a shallow opacity),
 * {@code torrente_Absorb} (rgb absorption per block, a biome tint), {@code torrente_Surface} (reflection, specular,
 * ripples, texture), {@code torrente_Waves} (swell height, foam, refraction, ripple octaves),
 * {@code torrente_Caustics} (strength, cells per block, fade depth, layers), {@code torrente_Mode} (x depth mode).
 */
final class WaterGlsl {
    private WaterGlsl() {
    }

    /** Ripple octaves the tables hold (HIGH uses 6). */
    private static final int RIPPLE_OCTAVES = 8;

    /**
     * Constant tables of the ripple octaves, the same series 0.1.9 built in its loop (float arithmetic replayed here,
     * so directions, frequencies and weights are unchanged): direction (sin, cos of the octave's angle), frequency
     * (x1.18 per octave), weight (x0.8), the summed weight of the first n octaves, and the time rate. The rate was
     * 2.0 x 1.07^i rad/s; it is now that rounded to whole cycles per hour (n x ATMOS_HOUR_CYCLE), so the hourly wrap
     * of atmos_Time.y never jumps. TORRENTE_RIP_FINE_TILT bounds the slope octaves 3+ can add (0.536 is the maximum
     * of exp(sin x - 1) cos x), for the glint gate in torrente_surfaceNormal.
     */
    private static String rippleTables() {
        StringBuilder dir = new StringBuilder();
        StringBuilder freq = new StringBuilder();
        StringBuilder weight = new StringBuilder();
        StringBuilder rate = new StringBuilder();
        StringBuilder sum = new StringBuilder("0.0");
        float iter = 0f;
        float f = 1f;
        float w = 1f;
        float tm = 2f;
        float total = 0f;
        double fineTilt = 0.0;
        for (int i = 0; i < RIPPLE_OCTAVES; i++) {
            String sep = i == 0 ? "" : ", ";
            dir.append(sep).append("vec2(").append(glsl(Math.sin(iter))).append(", ").append(glsl(Math.cos(iter))).append(')');
            freq.append(sep).append(glsl(f));
            weight.append(sep).append(glsl(w));
            rate.append(sep).append(glsl(Math.rint(tm * 3600.0 / (2.0 * Math.PI))));
            total += w;
            sum.append(", ").append(glsl(total));
            if (i >= 3 && i < 6) {
                fineTilt += 0.536 * f * w;
            }
            w *= 0.8f;
            f *= 1.18f;
            tm *= 1.07f;
            iter += 1232.399963f;
        }
        String n = Integer.toString(RIPPLE_OCTAVES);
        return "const vec2 TORRENTE_RIP_DIR[" + n + "] = vec2[" + n + "](" + dir + ");\n"
                + "const float TORRENTE_RIP_FREQ[" + n + "] = float[" + n + "](" + freq + ");\n"
                + "const float TORRENTE_RIP_W[" + n + "] = float[" + n + "](" + weight + ");\n"
                + "const float TORRENTE_RIP_RATE[" + n + "] = float[" + n + "](" + rate + ");\n"
                + "const float TORRENTE_RIP_SUM[" + (RIPPLE_OCTAVES + 1) + "] = float[" + (RIPPLE_OCTAVES + 1) + "](" + sum + ");\n"
                + "const float TORRENTE_RIP_FINE_TILT = " + glsl(fineTilt) + ";\n";
    }

    private static String glsl(double value) {
        return String.format(java.util.Locale.ROOT, "%.9g", value);
    }

    /**
     * Swell: three fixed-direction sines (the wind only scales them, so turning wind never spins the pattern).
     * Returns x height, yz slope d/dx, d/dz, in blocks. torrente_Waves.x is 0 while the waves feature is off.
     * <p>
     * Every rate on {@code atmos_Time.y} (which wraps at 3600 s) is a whole number of cycles per hour (0.1.10), so
     * nothing jumps at the wrap: swell 745 / 974 / 1490 (was 1.3 / 1.7 / 2.6 rad/s), foam 974 / 745, ripples from
     * {@link #rippleTables()}, caustic cells rounded per cell. Speeds changed by under 0.05%.
     */
    private static final String SWELL = """
            #ifndef ATMOS_HOUR_CYCLE
            #define ATMOS_HOUR_CYCLE 0.0017453293
            #endif
            vec3 torrente_swell(vec2 torrenteP) {
                const vec2 torrenteD1 = vec2(0.8, 0.6);
                const vec2 torrenteD2 = vec2(-0.36, 0.933);
                const vec2 torrenteD3 = vec2(0.95, -0.312);
                const vec3 torrenteK = vec3(0.55, 0.85, 1.6);
                const vec3 torrenteA = vec3(0.5, 0.3, 0.2);
                vec3 torrentePh = vec3(dot(torrenteP, torrenteD1), dot(torrenteP, torrenteD2), dot(torrenteP, torrenteD3))
                        * torrenteK - atmos_Time.y * (vec3(745.0, 974.0, 1490.0) * ATMOS_HOUR_CYCLE);
                vec3 torrenteC = torrenteA * torrenteK * cos(torrentePh);
                vec2 torrenteG = torrenteD1 * torrenteC.x + torrenteD2 * torrenteC.y + torrenteD3 * torrenteC.z;
                return vec3(dot(torrenteA, sin(torrentePh)), torrenteG) * torrente_Waves.x;
            }
            """;

    /** Ripples, hash and Voronoi cells (fragment-stage helpers, pure functions of position and time). */
    private static final String HELPERS = rippleTables() + """
            // Ripple slope: octaves of exp(sin) waves with drag, after afl_ext's "very fast procedural ocean" (MIT,
            // see THIRD_PARTY_NOTICES.md), with the slope summed analytically. Runs octaves from..to-1 of the same
            // series, so a caller can run the coarse octaves, look at the result and continue (P carries the drag).
            void torrente_rippleSteps(inout vec2 torrenteP, inout vec2 torrenteGrad, int torrenteFrom, int torrenteTo) {
                for (int i = torrenteFrom; i < torrenteTo; i++) {
                    vec2 torrenteD = TORRENTE_RIP_DIR[i];
                    float torrenteF = TORRENTE_RIP_FREQ[i];
                    float torrenteX = dot(torrenteD, torrenteP) * torrenteF
                            + atmos_Time.y * (TORRENTE_RIP_RATE[i] * ATMOS_HOUR_CYCLE);
                    float torrenteDx = exp(sin(torrenteX) - 1.0) * cos(torrenteX);
                    torrenteGrad += torrenteD * (torrenteDx * torrenteF * TORRENTE_RIP_W[i]);
                    torrenteP -= torrenteD * (torrenteDx * TORRENTE_RIP_W[i] * 0.2);
                }
            }
            vec2 torrente_hash(vec2 torrenteCell) {
                uvec2 torrenteQ = uvec2(ivec2(torrenteCell)) * uvec2(1597334673u, 3812015801u);
                uint torrenteN = (torrenteQ.x ^ torrenteQ.y) * 1597334673u;
                return vec2(uvec2(torrenteN, torrenteN * 0x2c1b3c6du) >> 8u) / 16777216.0;
            }
            // Distance to the nearest edge between animated Voronoi cells (0 on an edge).
            // torrenteRate: time scale of the layer (1.4, 1.68); each cell's rate is rounded to whole cycles per hour.
            float torrente_cells(vec2 torrenteP, float torrenteRate) {
                vec2 torrenteI = floor(torrenteP);
                vec2 torrenteF = fract(torrenteP);
                float torrenteD1 = 8.0;
                float torrenteD2 = 8.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec2 torrenteG = vec2(x, y);
                        vec2 torrenteH = torrente_hash(torrenteI + torrenteG);
                        vec2 torrenteW = floor(torrenteRate * (0.6 + 0.5 * torrenteH) * 572.957795 + 0.5) * ATMOS_HOUR_CYCLE;
                        vec2 torrenteO = 0.5 + 0.38 * sin(atmos_Time.y * torrenteW + 6.2831853 * torrenteH);
                        vec2 torrenteR = torrenteG + torrenteO - torrenteF;
                        float torrenteD = dot(torrenteR, torrenteR);
                        if (torrenteD < torrenteD1) {
                            torrenteD2 = torrenteD1;
                            torrenteD1 = torrenteD;
                        } else if (torrenteD < torrenteD2) {
                            torrenteD2 = torrenteD;
                        }
                    }
                }
                return sqrt(torrenteD2) - sqrt(torrenteD1);
            }
            """;

    /**
     * The water surface itself, shared by every host (Xenon terrain and Voxy LODs) so near and distant water are the
     * same function of the same inputs. A host supplies the camera-relative position, the geometric normal, the tint,
     * light, texture luma, skylight and the water thickness it could measure, and composes the result: a straight
     * colour (blended with alpha), or premultiplied colour plus "keep", the share of what lies behind that shows
     * through (refraction on Xenon, hardware blending on Voxy). The maths is the 0.1.x surface shading, unchanged.
     */
    private static final String SHADE = """
            // Sun visibility for the glint (Lumis sun shadows on Xenon terrain; stays 1 on LODs and without Lumis).
            float torrente_sunVis = 1.0;
            // Normal: swell slope plus ripples on top/bottom faces, faded with distance against aliasing. V: view
            // direction (eye to fragment). On HIGH, octaves 4-6 only run where they can matter (0.1.10): near the
            // sun or moon glint, which they break into sparkles, and where they are wider than about two pixels.
            // Elsewhere they only nudged the Fresnel term. Their weight fades, so no edge shows.
            vec3 torrente_surfaceNormal(vec3 torrenteWorld, vec3 torrenteGeo, vec3 torrenteV, float torrenteDist,
                                        out bool torrenteFlat) {
                vec3 torrenteN = torrenteGeo;
                torrenteFlat = abs(torrenteGeo.y) > 0.5;
                if (torrenteFlat) {
                    vec2 torrenteSlope = torrente_swell(torrenteWorld.xz).yz;
                    float torrenteRip = torrente_Surface.z * (1.0 + 0.6 * atmos_Time.w) / (1.0 + torrenteDist * 0.03);
                    int torrenteOct = clamp(int(torrente_Waves.w), 0, 8);
                    int torrenteCoarse = min(torrenteOct, 3);
                    float torrenteNorm = 0.3 / max(TORRENTE_RIP_SUM[torrenteOct], 1.0) * torrenteRip;
                    vec2 torrenteP = torrenteWorld.xz * 1.7;
                    vec2 torrenteG = vec2(0.0);
                    torrente_rippleSteps(torrenteP, torrenteG, 0, torrenteCoarse);
                    if (torrenteOct > torrenteCoarse) {
                        vec2 torrenteS = torrenteSlope + torrenteG * torrenteNorm;
                        vec3 torrenteNc = normalize(vec3(-torrenteS.x, 1.0, -torrenteS.y)) * sign(torrenteGeo.y);
                        vec3 torrenteL = atmos_SunDir.y > -0.05 ? atmos_SunDir.xyz : -atmos_SunDir.xyz;
                        vec3 torrenteH = normalize(torrenteL - torrenteV);
                        // Glint cone: the lobe (exponent 1200) is invisible beyond about 0.11 rad from H; the fine
                        // octaves tilt the normal by at most TORRENTE_RIP_FINE_TILT times the ripple scale (slope).
                        float torrenteCone = 0.11 + TORRENTE_RIP_FINE_TILT * torrenteNorm;
                        float torrenteAng2 = 2.0 * (1.0 - dot(torrenteNc, torrenteH));
                        float torrenteFine = 1.0 - smoothstep(torrenteCone * torrenteCone,
                                1.69 * torrenteCone * torrenteCone, torrenteAng2);
                        // Pixel footprint (blocks per pixel at about 70 degrees vertical field of view): the same
                        // function on Xenon and LOD water, so the hand-over stays seamless.
                        float torrenteBpp = torrenteDist * 1.4 * atmos_Screen.y;
                        torrenteFine *= 1.0 - smoothstep(0.4, 0.8, torrenteBpp);
                        if (torrenteFine > 0.0) {
                            vec2 torrenteGf = vec2(0.0);
                            torrente_rippleSteps(torrenteP, torrenteGf, torrenteCoarse, torrenteOct);
                            torrenteG += torrenteGf * torrenteFine;
                        }
                    }
                    torrenteSlope += torrenteG * torrenteNorm;
                    torrenteN = normalize(vec3(-torrenteSlope.x, 1.0, -torrenteSlope.y)) * sign(torrenteGeo.y);
                }
                return torrenteN;
            }
            // Colour: style colour steered by the biome tint (vanilla water tint = 1), times the light and texture.
            vec3 torrente_scatter(vec3 torrenteTint, vec3 torrenteLight, float torrenteLuma) {
                float torrenteDetail = mix(1.0, torrenteLuma / 0.72, torrente_Surface.w);
                float torrenteBiomeMix = torrente_Mode.x > 1.5 ? 1.0 : torrente_Absorb.a;
                vec3 torrenteBiome = clamp(torrenteTint / vec3(0.247, 0.463, 0.894), 0.0, 2.0);
                vec3 torrenteWater = clamp(torrente_Color.rgb * mix(vec3(1.0), torrenteBiome, torrenteBiomeMix), 0.0, 1.0);
                return torrenteWater * torrenteLight * torrenteDetail;
            }
            // Beer-Lambert transmittance over a view-ray thickness (grey in Bathymetry mode: its tint is the colour).
            vec3 torrente_transmittance(float torrenteThick) {
                vec3 torrenteT = exp(-torrente_Absorb.rgb * torrenteThick);
                if (torrente_Mode.x > 1.5) torrenteT = vec3(dot(torrenteT, vec3(0.333)));
                return torrenteT;
            }
            // Seen from below: Snell's window (about 48 degrees) is clear, outside it the surface reflects the water below.
            vec4 torrente_fromBelow(vec3 torrenteScatter, float torrenteCos) {
                float torrenteTir = 1.0 - smoothstep(0.6, 0.72, torrenteCos);
                return vec4(mix(torrenteScatter * 1.2, atmos_FogColor.rgb, torrenteTir * 0.7), mix(0.15, 0.92, torrenteTir));
            }
            // Top surface: body (transmittance), Fresnel sky reflection, sun/moon glint and shore foam, in both forms.
            void torrente_shadeTop(vec3 torrenteV, vec3 torrenteN, float torrenteCos, vec3 torrenteWorld, float torrenteSky,
                                   vec3 torrenteScatter, vec3 torrenteLight, vec3 torrenteT, float torrenteThick,
                                   bool torrenteMeasured, bool torrenteFlat,
                                   out vec4 torrenteStraight, out vec3 torrentePremul, out vec3 torrenteKeep) {
                float torrenteRain = atmos_Time.w;
                vec3 torrenteRgb = torrenteScatter;
                float torrenteAlpha = mix(torrente_Color.a, 1.0, 1.0 - dot(torrenteT, vec3(0.333)));
                // Shallow water still shows a little of its own colour.
                vec3 torrenteTs = torrenteT * (1.0 - torrente_Color.a * 0.5);
                torrentePremul = torrenteScatter * (1.0 - torrenteTs);
                torrenteKeep = torrenteTs;

                // Fresnel (Schlick, F0 0.02) sky reflection; covered water reflects much less sky.
                vec3 torrenteR = reflect(torrenteV, torrenteN);
                vec3 torrenteSkyCol = mix(atmos_FogColor.rgb, atmos_SkyColor.rgb, smoothstep(0.0, 0.35, torrenteR.y));
                torrenteSkyCol *= mix(0.12, 1.0, smoothstep(0.5, 0.95, torrenteSky));
                float torrenteF = (0.02 + 0.98 * pow(1.0 - torrenteCos, 5.0)) * torrente_Surface.x;
                torrenteRgb = mix(torrenteRgb, torrenteSkyCol, torrenteF);
                torrenteAlpha = mix(torrenteAlpha, 1.0, torrenteF);
                torrentePremul = mix(torrentePremul, torrenteSkyCol, torrenteF);
                torrenteKeep *= 1.0 - torrenteF;

                // Sun glint by day, a faint moon glint by night.
                float torrenteSunUp = atmos_SunDir.y;
                vec3 torrenteL = torrenteSunUp > -0.05 ? atmos_SunDir.xyz : -atmos_SunDir.xyz;
                float torrenteLi = torrenteSunUp > -0.05 ? smoothstep(-0.05, 0.12, torrenteSunUp)
                        : 0.12 * smoothstep(0.05, 0.2, -torrenteSunUp);
                vec3 torrenteH = normalize(torrenteL - torrenteV);
                float torrenteSpec = pow(max(dot(torrenteN, torrenteH), 0.0), 1200.0) * 3.0 * torrenteLi * torrente_sunVis
                        * torrente_Surface.y * smoothstep(0.85, 1.0, torrenteSky) * (1.0 - 0.85 * torrenteRain);
                vec3 torrenteSunCol = mix(vec3(1.0, 0.62, 0.35), vec3(1.0, 0.97, 0.9), smoothstep(0.0, 0.3, torrenteSunUp));
                torrenteRgb += torrenteSpec * torrenteSunCol;
                torrenteAlpha = max(torrenteAlpha, clamp(torrenteSpec, 0.0, 1.0));
                torrentePremul += torrenteSpec * torrenteSunCol;

                // Shore foam where the water above the floor is very shallow.
                if (torrenteMeasured && torrenteFlat && torrente_Waves.y > 0.0) {
                    float torrenteVert = torrenteThick * abs(torrenteV.y);
                    float torrenteNoise = 0.55 + 0.45 * sin(torrenteWorld.x * 3.1 + torrenteWorld.z * 2.3 + atmos_Time.y * (974.0 * ATMOS_HOUR_CYCLE))
                            * sin(torrenteWorld.z * 3.7 - torrenteWorld.x * 1.9 - atmos_Time.y * (745.0 * ATMOS_HOUR_CYCLE));
                    float torrenteFoam = (1.0 - smoothstep(0.0, 0.5, torrenteVert)) * torrenteNoise * torrente_Waves.y;
                    vec3 torrenteFoamCol = vec3(0.92) * torrenteLight;
                    torrenteRgb = mix(torrenteRgb, torrenteFoamCol, torrenteFoam);
                    torrenteAlpha = max(torrenteAlpha, torrenteFoam);
                    torrentePremul = mix(torrentePremul, torrenteFoamCol, torrenteFoam);
                    torrenteKeep *= 1.0 - torrenteFoam;
                }
                torrenteStraight = vec4(torrenteRgb, clamp(torrenteAlpha, 0.0, 1.0));
            }
            // Diagnostics (debug_channel): one shading input as colour, identical on both hosts.
            bool torrente_debug(vec3 torrenteTint, vec3 torrenteLight, float torrenteLuma, float torrenteThick,
                                vec3 torrenteKeep, out vec3 torrenteOut) {
                int torrenteChannel = int(torrente_Lod.y + 0.5);
                torrenteOut = vec3(0.0);
                if (torrenteChannel == 1) torrenteOut = torrenteTint;
                else if (torrenteChannel == 2) torrenteOut = torrenteLight;
                else if (torrenteChannel == 3) torrenteOut = vec3(torrenteLuma);
                else if (torrenteChannel == 4) torrenteOut = vec3(clamp(torrenteThick / 32.0, 0.0, 1.0));
                else if (torrenteChannel == 5) torrenteOut = torrenteKeep;
                return torrenteChannel != 0;
            }
            """;

    static final String VERT_DECL = """
            #ifndef TORRENTE_VERT_DECL
            #define TORRENTE_VERT_DECL
            out float torrente_vSky;
            out vec3 torrente_vTint;
            out vec3 torrente_vLight;
            """ + SWELL + """
            #endif
            """;

    /** After v_Color: raw skylight (depth proxy), biome tint and lightmap colour, kept apart for the water shading. */
    static final String VERT_POST_LIGHT = """
            #ifndef TORRENTE_VERT_OUT
            #define TORRENTE_VERT_OUT
                torrente_vSky = clamp(float(_vert_tex_light_coord.y & 255) / 240.0, 0.0, 1.0);
                torrente_vTint = _vert_color.rgb;
                torrente_vLight = _sample_lightmap(u_LightTex, _vert_tex_light_coord).rgb;
            #endif
            """;

    static final String FRAG_DECL = """
            #ifndef TORRENTE_FRAG_DECL
            #define TORRENTE_FRAG_DECL
            in float torrente_vSky;
            in vec3 torrente_vTint;
            in vec3 torrente_vLight;
            """ + SWELL + """
            """ + HELPERS + SHADE + """
            #endif
            """;

    /** Waves: vertex stage, translucent pass. Only top-surface heights move (see TORRENTE-DESIGN.md). */
    static String waves(String surface) {
        return """
                    if (%s(atmos_MaterialBits) != 0u) {
                        vec3 torrenteWorld = atmos_worldPos(position);
                        float torrenteFrac = fract(torrenteWorld.y);
                        if (torrenteFrac > 0.02 && torrenteFrac < 0.98) {
                            position.y += torrente_swell(torrenteWorld.xz).x;
                        }
                    }
                """.formatted(surface);
    }

    /** Caustics: fragment stage, solid and cutout passes, on blocks with the submerged bit. */
    static String caustics(String submerged) {
        return """
                    if (%s(v_AtmosMaterialBits) != 0u) {
                        // Face normal towards the eye, shared with the other features (the lib computes it once at the
                        // top of main(), 0.1.10): light from above, so downward faces get none and walls less.
                        vec3 torrenteFace = atmos_FaceNormal;
                        float torrenteDepth = 15.0 - torrente_vSky * 15.0;
                        float torrenteK = smoothstep(0.4, 1.6, torrenteDepth)
                                * (1.0 - smoothstep(torrente_Caustics.z * 0.45, torrente_Caustics.z, torrenteDepth))
                                * atmos_SunDir.w * (1.0 - 0.7 * atmos_Time.w) * torrente_Caustics.x
                                * clamp(0.6 + 0.4 * torrenteFace.y, 0.0, 1.0) * step(-0.5, torrenteFace.y);
                        // Voxy LOD seabeds have no caustics: fade them out before the LOD transition starts
                        if (atmos_Lod.w > 0.5) {
                            torrenteK *= 1.0 - smoothstep(atmos_Lod.x - 24.0, atmos_Lod.x, length(v_AtmosRelPos));
                        }
                #ifdef ATMOS_SUN_SHADOW
                        if (torrenteK > 0.003) {
                            torrenteK *= atmos_sunVisibility(v_AtmosRelPos, torrenteFace);
                        }
                #endif
                        if (torrenteK > 0.003) {
                            vec3 torrenteW = atmos_worldPos(v_AtmosRelPos);
                            vec2 torrenteP = (torrenteW.xz + torrenteW.y * vec2(0.37, 0.61)) * torrente_Caustics.y;
                            float torrenteC = 1.0 - smoothstep(0.0, 0.16, torrente_cells(torrenteP, 1.4));
                            if (torrente_Caustics.w > 1.5) {
                                float torrenteC2 = 1.0 - smoothstep(0.0, 0.2,
                                        torrente_cells(torrenteP * 1.37 + vec2(3.1, 1.7), 1.68));
                                torrenteC = 0.65 * torrenteC + 0.35 * torrenteC2;
                            }
                            diffuseColor.rgb *= 1.0 + torrenteC * torrenteC * torrenteK * vec3(1.5, 1.65, 1.7);
                        }
                    }
                """.formatted(submerged);
    }

    /**
     * Water surface on Xenon: fragment stage, translucent pass, on fluid quads with the surface bit. Replaces
     * diffuseColor (before fog). Refraction code is compiled in only while the refraction feature is active
     * ({@code refractionMacro}) and the scene colour copy exists. The shading itself is {@link #SHADE}.
     */
    static String surface(String surface, String refractionMacro) {
        return """
                    if (%1$s(v_AtmosMaterialBits) != 0u) {
                        vec3 torrenteRel = v_AtmosRelPos;
                        float torrenteDist = max(length(torrenteRel), 1e-3);
                        vec3 torrenteV = torrenteRel / torrenteDist;
                        vec3 torrenteGeo = normalize(cross(dFdx(torrenteRel), dFdy(torrenteRel)));
                        if (dot(torrenteGeo, torrenteV) > 0.0) torrenteGeo = -torrenteGeo;
                        vec3 torrenteWorld = atmos_worldPos(torrenteRel);
                        bool torrenteFlat;
                        vec3 torrenteN = torrente_surfaceNormal(torrenteWorld, torrenteGeo, torrenteV, torrenteDist, torrenteFlat);
                        float torrenteCos = clamp(dot(-torrenteV, torrenteN), 0.0, 1.0);

                        vec4 torrenteTex = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);
                #ifdef USE_VANILLA_COLOR_FORMAT
                        float torrenteShade = 1.0;
                #else
                        float torrenteShade = v_Color.a;
                #endif
                        vec3 torrenteLight = torrente_vLight * torrenteShade;
                        float torrenteLuma = dot(torrenteTex.rgb, vec3(0.299, 0.587, 0.114));
                        vec3 torrenteScatter = torrente_scatter(torrente_vTint, torrenteLight, torrenteLuma);

                        if (torrenteGeo.y < -0.5) {
                            diffuseColor = torrente_fromBelow(torrenteScatter, torrenteCos);
                        } else {
                            // Water thickness along the view ray, from the opaque scene behind.
                            float torrenteThick = 3.0;
                            bool torrenteMeasured = false;
                            float torrenteZ = atmos_linearDepth(gl_FragCoord.z);
                #ifdef ATMOS_SCENE_DEPTH
                            if (atmos_Flags.x > 0.5 && torrente_Mode.x > 0.5) {
                                float torrenteZs = atmos_linearDepth(atmos_sceneDepth());
                                torrenteThick = max(torrenteZs - torrenteZ, 0.0) * torrenteDist / max(torrenteZ, 1e-3);
                                torrenteMeasured = true;
                            }
                #endif
                            bool torrenteComposite = false;
                            vec3 torrenteBehind = vec3(0.0);
                #if defined(%2$s) && defined(ATMOS_SCENE_COLOR) && defined(ATMOS_SCENE_DEPTH)
                            if (atmos_Flags.y > 0.5 && atmos_Flags.x > 0.5) {
                                vec2 torrenteUv = gl_FragCoord.xy * atmos_Screen.xy;
                                vec2 torrenteBend = (torrenteN.xz - torrenteGeo.xz) * (torrente_Waves.z * 0.02)
                                        * clamp(torrenteThick * 0.5, 0.0, 1.0) / (1.0 + torrenteDist * 0.05);
                                vec2 torrenteUvR = clamp(torrenteUv + torrenteBend, vec2(0.0), vec2(1.0));
                                float torrenteZr = atmos_linearDepth(textureLod(atmos_SceneDepth, torrenteUvR, 0.0).r);
                                if (torrenteZr < torrenteZ) {
                                    torrenteUvR = torrenteUv;
                                    torrenteZr = atmos_linearDepth(atmos_sceneDepth());
                                }
                                if (torrente_Mode.x > 0.5) {
                                    torrenteThick = max(torrenteZr - torrenteZ, 0.0) * torrenteDist / max(torrenteZ, 1e-3);
                                }
                                torrenteBehind = textureLod(atmos_SceneColor, torrenteUvR, 0.0).rgb;
                                torrenteComposite = true;
                            }
                #endif
                            vec4 torrenteStraight;
                            vec3 torrentePremul;
                            vec3 torrenteKeep;
                #ifdef ATMOS_SUN_SHADOW
                            torrente_sunVis = atmos_sunVisibility(torrenteRel, torrenteGeo);
                #endif
                            torrente_shadeTop(torrenteV, torrenteN, torrenteCos, torrenteWorld, torrente_vSky, torrenteScatter,
                                    torrenteLight, torrente_transmittance(torrenteThick), torrenteThick, torrenteMeasured,
                                    torrenteFlat, torrenteStraight, torrentePremul, torrenteKeep);
                            diffuseColor = torrenteComposite ? vec4(torrenteBehind * torrenteKeep + torrentePremul, 1.0)
                                    : torrenteStraight;
                            vec3 torrenteDebug;
                            if (torrente_debug(torrente_vTint, torrenteLight, torrenteLuma, torrenteThick, torrenteKeep,
                                    torrenteDebug)) {
                                diffuseColor = vec4(torrenteDebug, 1.0);
                            }
                        }
                    }
                """.formatted(surface, refractionMacro);
    }

    // ---- Voxy LOD host (AtmospheraLib compat.voxy) -----------------------------------------------------------------

    /** LOD_DECL: the swell in both stages, the surface shading in the fragment stage. */
    static final String LOD_DECL = """
            #ifndef TORRENTE_LOD_DECL
            #define TORRENTE_LOD_DECL
            """ + SWELL + """
            #ifdef VOXY_EXT_STAGE_FRAGMENT
            """ + HELPERS + SHADE + """
            #endif
            #endif
            """;

    /**
     * LOD water surface: the same {@link #SHADE} with Voxy's inputs. Thickness comes from Voxy's opaque LOD depth, the
     * tint from Voxy's Bathymetry ramp (Bathymetry's vertex tint on Xenon). With refraction on, the composited form goes
     * to Voxy's premultiplied blending (what lies behind is the LOD seabed, unbent: the bend is far below a pixel at
     * LOD distances); otherwise the straight colour, as on Xenon without refraction.
     */
    static String lodSurface(String surface, String refractionMacro) {
        return """
                    if (%1$s(atmos_MaterialBits) != 0u && torrente_Lod.x > 0.5) {
                        vec3 torrenteRel = f.relPos;
                        float torrenteDist = max(length(torrenteRel), 1e-3);
                        vec3 torrenteV = torrenteRel / torrenteDist;
                        vec3 torrenteGeo = f.geoNormal;
                        vec3 torrenteWorld = atmos_worldPos(torrenteRel);
                        bool torrenteFlat;
                        vec3 torrenteN = torrente_surfaceNormal(torrenteWorld, torrenteGeo, torrenteV, torrenteDist, torrenteFlat);
                        float torrenteCos = clamp(dot(-torrenteV, torrenteN), 0.0, 1.0);
                        vec3 torrenteScatter = torrente_scatter(f.bathyTint, f.light,
                                dot(f.albedo.rgb, vec3(0.299, 0.587, 0.114)));
                        if (torrenteGeo.y < -0.5) {
                            f.colour = torrente_fromBelow(torrenteScatter, torrenteCos);
                        } else {
                            float torrenteThick = 3.0;
                            bool torrenteMeasured = false;
                            if (f.sceneDepth > 0.0 && torrente_Mode.x > 0.5) {
                                torrenteThick = max(f.sceneDepth - f.fragDepth, 0.0) * torrenteDist / max(f.fragDepth, 1e-3);
                                torrenteMeasured = true;
                            }
                            vec4 torrenteStraight;
                            vec3 torrentePremul;
                            vec3 torrenteKeep;
                            torrente_shadeTop(torrenteV, torrenteN, torrenteCos, torrenteWorld, f.sky, torrenteScatter,
                                    f.light, torrente_transmittance(torrenteThick), torrenteThick, torrenteMeasured,
                                    torrenteFlat, torrenteStraight, torrentePremul, torrenteKeep);
                #if defined(%2$s)
                            f.colour = vec4(torrentePremul, 1.0);
                            f.keep = torrenteKeep;
                            f.premultiplied = true;
                #else
                            f.colour = torrenteStraight;
                #endif
                            vec3 torrenteDebug;
                            if (torrente_debug(f.bathyTint, f.light, dot(f.albedo.rgb, vec3(0.299, 0.587, 0.114)),
                                    torrenteThick, torrenteKeep, torrenteDebug)) {
                                f.colour = vec4(torrenteDebug, 1.0);
                                f.keep = vec3(0.0);
                            }
                        }
                    }
                """.formatted(surface, refractionMacro);
    }

    /**
     * LOD waves: top faces of sloped water quads (Voxy keeps them one voxel wide with shared corners, so nothing
     * cracks). Faded out past twice the transition end, where 0.1 blocks is far below a pixel.
     */
    static String lodWaves(String surface) {
        return """
                    if (%s(atmos_MaterialBits) != 0u && v.face == 1u && (v.quadFlags & VOXY_EXT_QUAD_FLUID_SHAPE) != 0u
                            && torrente_Lod.x > 0.5) {
                        float torrenteFade = 1.0 - smoothstep(atmos_Lod.y, atmos_Lod.y * 2.0, length(position));
                        if (torrenteFade > 0.0) {
                            position.y += torrente_swell(atmos_worldPos(position).xz).x * torrenteFade;
                        }
                    }
                """.formatted(surface);
    }
}
