# Artifact checks — 2 October 2026

- Blender 5.0 completed the build and saved the editable scene plus assembled, exploded and circuit renders.
- The ring assembly contains 48 named mesh objects. The browser geometry has 24,730 triangles after evaluation.
- GLB headers and embedded scene JSON were read successfully. Explicit 0.001 scale transforms convert the millimetre model coordinates to glTF metres; the shell is approximately 24.6 mm across.
- BOM allowances were summed from the CSV: INR 2,920–7,655.
- The offline viewer was opened in the Codex browser. Assembled, exploded and circuit views were exercised and visually inspected. Depth-buffered rendering was visually checked after replacing the initial painter rendering.
- The assembled and exploded Blender renders were visually inspected.

These checks establish artifact generation and presentation only. They do **not** verify electrical function, complete component fit, PCB routing, antenna impedance, battery compatibility, charging safety, waterproofing, battery life, phone background reliability, or emergency delivery. No firmware or app integration was implemented or tested.
