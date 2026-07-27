#version 150

in vec3 Position;

out vec2 texCoord;

void main() {
    // Position приходит уже в NDC (-1..1), рисуем полноэкранный квад
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
