#version 150

uniform float time;

in vec4 v_Colour;
in vec2 v_TexCoord;

out vec4 fragColor;

void main() {
    float t     = (time / 2.5) * 2.0 * 3.14159;
    float theta = (v_TexCoord.x + v_TexCoord.y) * 5.0;
    float k     = (sin(theta + t) + 1.0) * 0.5 + 0.25;
    fragColor   = mix(v_Colour * 0.25, v_Colour, k);
}
