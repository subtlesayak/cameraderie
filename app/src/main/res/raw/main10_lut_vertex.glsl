#version 300 es

out highp vec2 uv;

void main() {
    // One triangle covers the viewport. Interpolation gives UV 0–1 across the visible area.
    uv = vec2(gl_VertexID == 1 ? 2.0 : 0.0, gl_VertexID == 2 ? 2.0 : 0.0);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
