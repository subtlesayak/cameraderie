#version 300 es

precision highp float;
precision highp int;

uniform highp sampler2D sourceFrame;
uniform highp sampler2D colorCube;
uniform float cubeSize;
uniform int tilesPerRow;
in highp vec2 uv;
out highp vec4 color;

vec3 sampleSlice(vec2 rg, int slice) {
    vec2 origin = vec2(slice % tilesPerRow, slice / tilesPerRow) * cubeSize;
    vec2 atlasSize = vec2(textureSize(colorCube, 0));
    return texture(colorCube, (origin + rg + 0.5) / atlasSize).rgb;
}

void main() {
    vec4 source = texture(sourceFrame, uv);
    // GL_LINEAR interpolates red and green inside each tile. Mix the two
    // adjacent blue slices explicitly, even when they occupy different rows.
    vec3 coordinate = clamp(source.rgb, 0.0, 1.0) * (cubeSize - 1.0);
    int first = int(floor(coordinate.b));
    int second = min(first + 1, int(cubeSize) - 1);
    vec3 rgb = mix(sampleSlice(coordinate.rg, first), sampleSlice(coordinate.rg, second), fract(coordinate.b));
    color = vec4(rgb, source.a);
}
