#version 300 es

precision highp float;

uniform highp sampler2D sourceFrame;
uniform highp sampler3D colorCube;
uniform float cubeSize;
in highp vec2 uv;
out highp vec4 color;

void main() {
    vec4 source = texture(sourceFrame, uv);
    // Map 0 and 1 to the centers of the first and last texels. GL_LINEAR interpolates
    // between all three .cube axes in floating point.
    vec3 coordinate = (clamp(source.rgb, 0.0, 1.0) * (cubeSize - 1.0) + 0.5) / cubeSize;
    color = vec4(texture(colorCube, coordinate).rgb, source.a);
}
