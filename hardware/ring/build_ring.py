"""Generate the S4H trigger-ring concept. Run with Blender 5 in background.
Geometry coordinates are millimetres. Component bodies are placement envelopes,
not manufacturer ECAD. No geometry in this file constitutes a routed PCB.
"""
import bpy, math, json, struct
from pathlib import Path
from mathutils import Vector

OUT = Path(__file__).resolve().parent
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
scene = bpy.context.scene
scene.unit_settings.system = 'METRIC'
scene.unit_settings.scale_length = 0.001
scene.unit_settings.length_unit = 'MILLIMETERS'
parts = []

def material(name, color, metal=0, rough=.35):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*color, 1)
    m.use_nodes = True
    bs = m.node_tree.nodes.get('Principled BSDF')
    bs.inputs['Base Color'].default_value = (*color, 1)
    bs.inputs['Metallic'].default_value = metal
    bs.inputs['Roughness'].default_value = rough
    return m

titanium = material('Satin titanium appearance', (.32,.35,.38), .85,.26)
liner = material('Insulating polymer liner', (.035,.047,.055), 0,.28)
rf = material('Nonconductive RF window', (.07,.08,.09), 0,.34)
pcb = material('Polyimide flex', (.32,.16,.045), .1,.45)
green = material('Rigid PCB islands', (.02,.16,.12), .1,.4)
gold = material('Gold contacts and conceptual traces', (.83,.52,.15), .75,.26)
black = material('IC packages', (.025,.03,.035), .1,.3)
silver = material('Battery envelope', (.53,.59,.63), .55,.3)
red = material('Recessed SOS membrane', (.66,.045,.085), .05,.3)
ceramic = material('Passives', (.57,.50,.36), .1,.45)
ledmat = material('Status light pipe', (.15,.78,.59), .1,.25)

def register(o, mat, group, explode=(0,0,0)):
    o.data.materials.append(mat)
    o['group'] = group
    o['exploded_offset_mm'] = explode
    o['design_status'] = 'CONCEPT / envelope only / not fabrication ready'
    parts.append(o)
    return o

def arc(name, ri, ro, z0, z1, a0=0, a1=360, mat=titanium, group='shell', explode=(0,0,13), concave=False):
    n = max(8, int((a1-a0)/3))
    # Closed annular sector, with a shallow concave outside profile for the shell.
    profile = [(ri,z0),(ro,z0),(ro-.12 if concave else ro,(z0+z1)/2),(ro,z1),(ri,z1)]
    verts=[]
    for i in range(n+1):
        a=math.radians(a0+(a1-a0)*i/n)
        verts.extend([(r*math.cos(a),r*math.sin(a),z) for r,z in profile])
    k=len(profile); faces=[]
    for i in range(n):
        for j in range(k):
            faces.append((i*k+j,i*k+(j+1)%k,(i+1)*k+(j+1)%k,(i+1)*k+j))
    faces += [tuple(reversed(range(k))),tuple(n*k+j for j in range(k))]
    mesh=bpy.data.meshes.new(name); mesh.from_pydata(verts,[],faces); mesh.update()
    o=bpy.data.objects.new(name,mesh); scene.collection.objects.link(o)
    register(o,mat,group,explode)
    bevel=o.modifiers.new('Soft edges','BEVEL'); bevel.width=.06; bevel.segments=2
    o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')
    return o

def box(name, xyz, dims, mat, group, angle=0, explode=(0,0,0), bevel=.07):
    bpy.ops.mesh.primitive_cube_add(size=1,location=xyz)
    o=bpy.context.object; o.name=name; o.dimensions=dims
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    o.rotation_euler.z=math.radians(angle)
    register(o,mat,group,explode)
    if bevel:
        b=o.modifiers.new('Soft package edges','BEVEL'); b.width=bevel; b.segments=2
        o.modifiers.new('Weighted normals','WEIGHTED_NORMAL')
    return o

def radial_box(name, angle, radius, tangent, axial, thick, mat, group='electronics', z=0, explode=(0,0,0)):
    a=math.radians(angle)
    return box(name,(radius*math.cos(a),radius*math.sin(a),z),(thick,tangent,axial),mat,group,angle,explode,.025)

# 18.2 mm ID; 24.6 mm maximum OD; 8 mm axial width. Discontinuous metal shell
# leaves an RF window and a separate trigger membrane (no continuous metal loop).
arc('01 / titanium shell A',11.8,12.3,-4,4,80,265,concave=True)
arc('01 / titanium shell B',11.8,12.3,-4,4,320,424,concave=True)
arc('02 / polymer antenna window',11.8,12.3,-4,4,265,320,rf,'shell',(0,0,13),True)
arc('03 / recessed trigger membrane',11.85,12.13,-2.1,2.1,64,80,red,'button',(4,7,5))
arc('03 / upper button surround',11.8,12.3,2.1,4,64,80,rf,'shell')
arc('03 / lower button surround',11.8,12.3,-4,-2.1,64,80,rf,'shell')
arc('04 / finger liner',9.1,9.65,-4,4,mat=liner,group='liner',explode=(0,0,-13))
arc('05 / upper insulating closure',9.65,11.8,3.6,4,mat=liner,group='liner',explode=(0,0,-13))
arc('05 / lower insulating closure',9.65,11.8,-4,-3.6,mat=liner,group='liner',explode=(0,0,-13))

# The battery's centreline arc is 26 mm, with a 1.7 x 6.5 mm cross section.
# Supplier must confirm that published "length" and radius match this envelope.
battery_angle=math.degrees(26/10.75)
arc('06 / GRP170726 battery ENVELOPE 18.5mAh',9.9,11.6,-3.25,3.25,110,110+battery_angle,silver,'battery',(-9,4,0))
for a in [110,110+battery_angle]:
    radial_box('Battery tab reserve',a,10.75,1.4,1,.08,gold,'battery',z=2.7,explode=(-9,4,0))

arc('07 / flex interconnect A',9.80,9.98,-2.8,2.8,250,360,pcb,'electronics')
arc('07 / flex interconnect B',9.80,9.98,-2.8,2.8,0,108,pcb,'electronics')
for a in [-33,0,35,72]:
    radial_box('Rigid island '+str(a),a,10.17,5.1 if a==0 else 3.9,6,.32,green)
radial_box('U1 / nRF52832-CIAA-G / 3.0 x 3.2 mm',0,10.57,3.0,3.2,.40,black)
radial_box('U2 / BQ25101 / 1.6 x 0.9 mm',-33,10.58,1.6,.9,.55,black,z=1.4)
radial_box('U3 / TPS7A0230PDQNR / 1 x 1 mm',35,10.55,1,1,.55,black,z=1.5)
radial_box('Protection circuit allocation - IC and dual FET TBD',35,10.68,2.7,1.8,.75,black,z=-1.25)
radial_box('SW1 / SKTAAAE010 envelope',72,10.61,2.6,1.6,.53,silver)
radial_box('Button plunger allocation',72,11.33,1.7,1.1,.85,rf,'button',explode=(4,7,5))
radial_box('Y1 / 32MHz crystal envelope',0,10.70,2,1.2,.7,silver,z=2.45)
radial_box('Y2 / 32.768kHz crystal envelope',-33,10.7,2,1.2,.8,silver,z=-1.4)
for a,z in [(0,-2.4),(0,-1.95),(-33,0),(-33,-.5),(35,2.4)]:
    radial_box('0201 passive allocation',a,10.49,.6,.3,.3,ceramic,z=z)
for a in [7,10,13]:
    radial_box('RF matching allocation',a,10.50,.6,.3,.3,ceramic,z=-2.3)
radial_box('Cell NTC allocation',102,10.3,1,.5,.45,ceramic)

# Antenna is a visible allocation, NOT an impedance tuned antenna design.
arc('08 / antenna flex zone - no ground beneath',10.02,10.14,-2.6,2.6,272,311,pcb,'antenna',(5,-7,0))
for z in [-1.8,-.6,.6,1.8]:
    arc('Antenna concept copper - NOT RF tuned',10.16,10.20,z,z+.12,277,306,gold,'antenna',(5,-7,0))
for a,z in [(277,-1.2),(306,0),(277,1.2)]:
    radial_box('Antenna concept meander',a,10.20,.12,1.32,.06,gold,'antenna',z=z,explode=(5,-7,0))
for z in [-2.1,2.1]:
    arc('Concept interconnect - not routed nets',10,10.03,z,z+.10,-43,105,gold,'electronics')

# Two flush pads on the lower axial rim; no exposed battery terminals.
for a in [17,28]:
    aa=math.radians(a)
    box('09 / gold charging pad',(10.7*math.cos(aa),10.7*math.sin(aa),-4.04),(1.2,1.1,.12),gold,'contacts',a,(0,0,-5),.03)
radial_box('10 / status light pipe allocation',56,12.06,.65,.65,.25,ledmat,'button',z=0,explode=(4,7,5))

# USB-C dock shown separately in its own export. Mechanical placeholders only.
ring_parts=list(parts)
dock_parts=[]
def dock_add(o): dock_parts.append(o); return o
dock_add(box('Dock / base',(0,0,-13),(34,34,6),rf,'dock',bevel=2))
arc_obj=arc('Dock / locating post',0.1,8.95,-10,-3.8,mat=liner,group='dock',explode=(0,0,0)); dock_add(arc_obj)
for a in [17,28]:
    aa=math.radians(a)
    dock_add(box('Dock / pogo contact',(10.7*math.cos(aa),10.7*math.sin(aa),-6.9),(.65,.65,5.8),gold,'dock',bevel=.18))
dock_add(box('Dock / USB-C opening indication',(0,-17.02,-13),(8.7,.4,3.1),black,'dock',bevel=.6))
dock_add(box('Dock / keyed stop',(13,0,-8),(1.2,3,4),liner,'dock'))

def select_objects(objs):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active=objs[0]

def export_glb(filename,objs):
    select_objects(objs)
    # Blender's exporter retains coordinate values; scene display units alone
    # do not turn our millimetre coordinates into glTF's required metres.
    saved=[(o,o.location.copy(),o.scale.copy()) for o in objs]
    try:
        for o,loc,scale in saved:
            o.location=loc*.001
            o.scale=scale*.001
        bpy.context.view_layer.update()
        bpy.ops.export_scene.gltf(filepath=str(OUT/filename),use_selection=True,export_format='GLB',export_apply=True)
    finally:
        for o,loc,scale in saved:
            o.location=loc
            o.scale=scale
        bpy.context.view_layer.update()

export_glb('s4h-ring.glb',ring_parts)
export_glb('s4h-ring-with-dock.glb',parts)
select_objects([o for o in ring_parts if o['group'] in ['shell','liner','button','contacts']])
bpy.ops.wm.stl_export(filepath=str(OUT/'ring-envelope-mm.stl'),export_selected_objects=True,apply_modifiers=True,use_scene_unit=False)

# Export actual evaluated geometry for the dependency-free browser viewer.
def mesh_data(objs):
    out=[]; deps=bpy.context.evaluated_depsgraph_get()
    for o in objs:
        ev=o.evaluated_get(deps); me=ev.to_mesh(); me.calc_loop_triangles()
        verts=[list(o.matrix_world@v.co) for v in me.vertices]
        out.append(dict(name=o.name,group=o['group'],color=list(o.data.materials[0].diffuse_color)[:3],
                        offset=list(o['exploded_offset_mm']),v=verts,f=[list(t.vertices) for t in me.loop_triangles]))
        ev.to_mesh_clear()
    return out
data=mesh_data(ring_parts)
(OUT/'model-data.json').write_text(json.dumps(data,separators=(',',':')),encoding='utf-8')

# Studio renders are generated from the same model as the exports.
for o in dock_parts: o.hide_render=True; o.hide_set(True)
scene.render.engine='CYCLES'; scene.cycles.samples=40
scene.cycles.use_denoising=True
scene.render.resolution_x=1600; scene.render.resolution_y=1200; scene.render.resolution_percentage=100
scene.world.color=(.25,.25,.25)
scene.view_settings.view_transform='AgX'
bpy.ops.object.camera_add(location=(60,40,48))
camera=bpy.context.object; camera.name='Presentation camera'; scene.camera=camera
def point(o,at): o.rotation_euler=(Vector(at)-o.location).to_track_quat('-Z','Y').to_euler()
point(camera,(0,0,0)); camera.data.type='ORTHO'; camera.data.ortho_scale=42
for name,loc,power,size in [('Key',(12,-28,45),18000,30),('Fill',(-30,-10,20),14000,26),('Rim',(0,30,30),22000,22)]:
    bpy.ops.object.light_add(type='AREA',location=loc)
    o=bpy.context.object; o.name=name; o.data.energy=power; o.data.shape='DISK'; o.data.size=size; point(o,(0,0,0))
scene.render.image_settings.file_format='PNG'
scene.render.film_transparent=False
scene.world.use_nodes=True
scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.075,.09,.11,1)
scene.world.node_tree.nodes['Background'].inputs[1].default_value=.5
scene.render.filepath=str(OUT/'ring-assembled.png'); bpy.ops.render.render(write_still=True)
camera.location=(40,-60,48); point(camera,(0,0,0))
for o in ring_parts: o.location += Vector(o['exploded_offset_mm'])
camera.data.ortho_scale=65
scene.render.filepath=str(OUT/'ring-exploded.png'); bpy.ops.render.render(write_still=True)
export_glb('s4h-ring-exploded.glb',ring_parts)
for o in ring_parts: o.location -= Vector(o['exploded_offset_mm'])
for o in ring_parts: o.hide_render=o['group'] in ['shell','liner','button','contacts']
camera.location=(36,-48,48); point(camera,(0,0,0)); camera.data.ortho_scale=35
scene.render.filepath=str(OUT/'ring-circuit.png'); bpy.ops.render.render(write_still=True)
for o in ring_parts: o.hide_render=False
camera.location=(60,40,48); point(camera,(0,0,0)); camera.data.ortho_scale=42
scene['README']='Concept only. Read README.md. Units mm. Battery curvature unconfirmed; circuit shapes are envelopes, traces illustrative. Dock objects hidden in viewport.'
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'s4h-ring.blend'))
print('RING_EXPORT_COMPLETE',len(ring_parts),'ring objects')
