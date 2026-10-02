# Security4Her button ring — concept R1

Prepared 2 October 2026. Currency: INR. This package is a mechanical/electrical **concept**, not a manufactured or tested emergency device.

## Open the model

- `ring-viewer.html`: self-contained, offline 3D viewer; drag to rotate, scroll to zoom, switch between assembled, exploded and circuit views.
- `s4h-ring.blend`: editable Blender assembly, named components, millimetre scene units, camera and lighting.
- `s4h-ring.glb`, `s4h-ring-exploded.glb`: coloured 3D assemblies; glTF uses metres.
- `s4h-ring-with-dock.glb`: ring and a USB-C contact-dock concept.
- `ring-envelope-mm.stl`: exterior appearance/fit reference, in millimetres. Multiple overlapping bodies; **not** a production enclosure or a ready-to-print sealed assembly. Use the Blender source to develop printable parts.
- `ring-assembled.png`, `ring-exploded.png`, `ring-circuit.png`: renders of that same geometry.
- `circuit.svg`: functional circuit architecture. `bom.csv`: editable cost allowances.
- Rebuild models with Blender: `blender --background --python build_ring.py`; run `python make_viewer.py` afterward to embed the geometry in the viewer.

## What to build

A BLE peripheral with one recessed mechanical button. Hold for 1.5 seconds to send an SOS request to the paired phone. No gyro, accelerometer, health sensors, GPS, cellular modem, microphone, or vibration motor. The phone supplies location and performs the existing SOS actions. A small status LED is optional but included in the allocation. The battery NTC is a charging-safety component, not a health sensor.

Keep the smooth band and shallow concave exterior associated with Samsung's design, with an electrically insulating inner liner and a nonconductive antenna window. Samsung publishes 7 mm width and 2.6 mm thickness; this concept deliberately leaves more packaging space. [Samsung dimensions](https://www.samsung.com/us/support/answer/ANS10003278/), [Samsung width](https://www.samsung.com/uk/rings/galaxy-ring/galaxy-ring-titanium-silver-size-6-sm-q506nzsaeub/?modelCode=SM-Q506NZSAEUB).

| Dimension | Concept R1 |
|---|---:|
| Finger opening | 18.2 mm diameter, provisional |
| Outer diameter | 24.6 mm nominal maximum |
| Radial thickness | 3.2 mm |
| Band width, along finger | 8.0 mm |
| Inner liner thickness | 0.55 mm radial |
| Outer shell thickness | approximately 0.5 mm |
| Battery allocation | 26 mm centreline arc × 6.5 mm axial × 1.7 mm radial |
| Battery envelope centreline radius | 10.75 mm — supplier confirmation required |

The charging-pad faces protrude about 0.1 mm beyond the lower axial rim in the visual model. Actual flush fit, sealing, adhesive gaps, swelling allowance, strain relief, edge radii and tolerances need mechanical development. Changing ring size requires a new fit study; do not simply scale the electronics. No weight or waterproof rating is claimed.

Start with a nonconductive polymer housing with a satin grey finish for RF development. The final cosmetic metal sections shown here must be tuned and tested with the antenna, battery and a finger present. Metal coating on the RF window would defeat its purpose. The illustrated antenna is an allocation, not a working antenna geometry; a matching network and final on-body RF measurements are essential.

## Components and cost

These are **planning allowances per ring in small prototype quantities**, not supplier quotations. Custom battery, PCB and enclosure minimum orders and setup fees may dominate a one-off build. Prices exclude tax, shipping, import charges, engineering labour, tooling, certification and failed revisions.

| Item | Proposed part / implementation | Allowance |
|---|---|---:|
| BLE MCU | Nordic **nRF52832-CIAA-G-R7**, WLCSP | ₹400–700 |
| Charger | TI **BQ25101YFPR**, 4.2 V cell, nominal 10 mA charge | ₹100–220 |
| 3 V regulator | TI **TPS7A0230PDQNR**, small X2SON variant | ₹40–80 |
| Cell protection | Matched 1S protection IC + back-to-back MOSFETs; thresholds and exact parts TBD with cell supplier | ₹60–150 |
| Trigger | Alps Alpine **SKTAAAE010**, 1.6 N tactile switch, membrane/plunger | ₹40–80 |
| RF and support | 32 MHz crystal, 32.768 kHz crystal, matching/decoupling, NTC, resistors, SWD pads | ₹120–250 |
| Status light | Low-current LED, resistor and light pipe | ₹10–25 |
| Curved cell | Grepow **GRP170726**, specified 4.2 V version, 18.5 mAh | ₹400–1,000, quotation needed |
| Custom board | Rigid-flex or flex with rigid islands; WLCSP assembly capability | ₹500–1,500 |
| Body | Polymer liner, shell, button seal, contact feedthroughs | ₹500–1,500 |
| Assembly / test | Fine-pitch assembly, programming and unit checks | ₹500–1,500 |
| Dock | USB-C input, protected 5 V, keyed cradle, two pogo contacts | ₹250–650 |
| **Total recurring prototype allowance** | **Ring plus dock** | **₹2,920–7,655** |

As an indicative price check, Mouser India listed the older `nRF52832-CIAA-R` at ₹429.56 for one and ₹273.95 at 100 when researched. This is only a family price reference: **Nordic marks that older non-G ordering code as not recommended for new designs**. The recommended G revision's current quote was not verified. Do not substitute solely on price. [Mouser listing](https://www.mouser.in/en/ProductDetail/Nordic-Semiconductor/nRF52832-CIAA-R), [Nordic order codes](https://docs.nordicsemi.com/r/bundle/ps_nrf52832/page/ordering_info.html).

Budget roughly ₹2,000–5,000 for a desk-sized BLE-button proof of concept using development hardware; it will not fit this band. Allow roughly ₹15,000–40,000+ for a first custom ring hardware iteration **excluding engineering labour**. These are rough planning estimates, not purchasable offers; a custom-cell MOQ can exceed that budget. A reliable production price cannot be established until quantity, cell quotation and manufacturing yield are known.

The MCU's WLCSP is nominally 3.0 × 3.2 mm, not the much larger development board. Use Nordic's exact reference circuit and RF layout as the electrical starting point. [Nordic specification](https://docs.nordicsemi.com/r/bundle/ps_nrf52832/page/nrf52832_ps.html), [reference layouts](https://www.nordicsemi.com/products/nrf52832/download). The switch body is 2.6 × 1.6 × 0.53 mm; switch sealing does not certify a complete ring. [Alps switch family](https://tech.alpsalpine.com/c/products/category/tact-switch/sub/02/series/skta/).

## Battery and charging

The candidate GRP170726 is listed by Grepow at **18.5 mAh**, **1.7 × 6.5 × 26 mm**, with **4.2 V full-charge voltage**. The supplier must confirm nominal voltage, bend radius, interpretation of length, terminal positions, discharge pulses, protection and availability. The 3D object reproduces an assumed envelope, not supplier CAD. Do not bend an ordinary flat LiPo into this shape. [Grepow cell table](https://www.grepow.com/wearables/smart-ring.html).

Use a nominal 10 mA CC/CV charge target, subject to the chosen cell's approved limits. BQ25101 is the 4.2 V variant; its 13.5 kΩ ISET resistor gives a nominal 10 mA target. Include input/output capacitors, a thermally coupled 10 kΩ NTC with the specified characteristic, and separate cell protection. The status output is open-drain; pull it up to the regulated logic supply. BQ25101 is not a full power-path manager, so verify termination with the ring's connected load. Do not treat charger thermal regulation as battery temperature protection. [TI charger datasheet](https://www.ti.com/lit/ds/symlink/bq25101.pdf).

Estimated charging time is **2.5–3 hours**, including the constant-voltage tail; it is not measured. An 18.5 mAh / 10 mA division alone understates full charging time. The dock accepts USB-C 5 V with the appropriate sink CC resistors, current limiting, reverse/contact-fault protection and ESD protection. The ring receives protected 5 V and ground through two pads; the battery NTC stays inside the ring. Charging is off-finger in a keyed cradle. Do not expose raw battery terminals. USB-C belongs on the dock, not the ring.

Inductive charging is possible later but needs receiver electronics, a coil, alignment development and a new thermal/RF study. Contact charging is the simpler first build. A generic TP4056 module at its usual charge current is unsuitable for this tiny cell.

The nRF52832 supply range is 1.7–3.6 V. **Do not connect a fully charged 4.2 V LiPo directly to it.** Use the 3.0 V regulator and the specified decoupling; select dropout and battery cutoff together. [Nordic electrical limits](https://docs.nordicsemi.com/r/bundle/ps_nrf52832/page/nrf52832_ps.html), [TI regulator datasheet](https://www.ti.com/lit/ds/symlink/tps7a02.pdf).

### Battery-life estimate, not a measured claim

Assume 70% of labelled capacity is usable after reserve, ageing and operating-voltage limits: 18.5 × 0.70 = 12.95 mAh. Average currents below mean the **whole ring at the battery**, not the MCU sleep current alone.

| Average current | Calculated runtime |
|---:|---:|
| 30 µA | 18.0 days |
| 50 µA | 10.8 days |
| 100 µA | 5.4 days |
| 200 µA | 2.7 days |

An initial engineering target of **5–10 days** is reasonable to investigate, not to advertise. Radio bursts, reconnections, poor signal near the body, frequent LED use, leakage and phone-imposed connection intervals can materially reduce it. The cell must support the actual radio peak current without brownout. Measure connected, disconnected, retrying, charging and low-battery states with a current profiler.

## Circuit architecture and firmware

`circuit.svg` describes the functional connections. The 3D PCB shows placement areas, not real copper routing or all required passives. A complete schematic/netlist, exact protection parts, RF matching, footprints, PCB stack-up, DRC/ERC and fabrication outputs remain to be engineered.

1. Button closes to ground on an interrupt-capable GPIO, with a suitable pull-up. Debounce, require a continuous 1.5 s hold, then latch exactly one event until release.
2. Bond to one owner phone. Enter pairing only by an intentional action such as holding the button while docked. Use LE Secure Connections plus a reviewed ownership-authentication scheme (for example a unique QR claim secret); no-input/no-output Just Works pairing alone does not prove ownership against an active attacker.
3. Send an authenticated event with protocol version, device identity, boot/session nonce and monotonic event counter. The phone persists the mapping to an incident UUID and deduplicates retransmissions. No SOS via unauthenticated advertising.
4. Use a custom GATT event characteristic plus an application ACK/status characteristic and standard battery information. A GATT indication acknowledgment alone proves transport reception, not an SOS incident or delivered messages.
5. The app returns distinct states for receipt, SOS accepted and downstream delivery outcome. The LED must never show delivered just because a BLE packet was sent. No motor is included, so discreet tactile confirmation is absent; the phone can vibrate on acceptance.
6. If disconnected, attempt bounded reconnection and retry the same event for a proposed 30 seconds. Show failure if no app acknowledgment arrives. Do not start a stale SOS minutes later after a delayed reconnect. Final timeouts and indications need user testing.
7. Use battery monitoring through a suitably scaled, preferably switched divider, watchdog and brownout handling, low-battery warnings, secure bond storage and signed firmware-update design. Do not feed battery voltage directly into a GPIO or ADC.

## Fit with this repository

Inspected `mobile/src/main/java/in/security4her/app/sos/Sos.kt`, `mobile/src/main/AndroidManifest.xml`, `CONTRACT.md`, and `ios/Security4Her/Info.plist`. The Android entry point is `SosEngine.start(ctx, id, trigger, source, fix, reason)` at line 143. The current watch transport is the Wear OS Data Layer. No generic BLE ring transport was found in the inspected mobile/iOS source. **I cannot confirm ring support from the current codebase.** This package does not change app code or implement firmware.

Android integration should add companion-device association, BLE permissions appropriate to each OS version, GATT subscription, reconnect handling, and a suitable companion/connected-device service for sustained background listening. Pairing alone does not establish or maintain the connection. Respect foreground-service restrictions; starting location, microphone, camera or an activity from a background BLE callback is not automatically permitted. [Android BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [companion pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing).

After authentication and persistent deduplication, the adapter can feed the existing SOS engine using `trigger="button"`, `source="phone"` within today's contract. Preserve the ring device/event metadata separately. If first-class `ring` trigger/source values are desired, revise `CONTRACT.md`, database validation, shared models and tests together; do not send unsupported values to the backend.

iOS would require Core Bluetooth central background mode, restoration, and an independently tested SOS path. Its current plist lists audio/location background modes, not Bluetooth. Background execution is limited; do not promise that force-quitting the app still permits SOS activation. [Apple background processing](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html).

## Required validation before relying on it

The ring has no independent emergency communications. Phone off, Bluetooth off, out of range, missing permissions or force-stop can prevent activation. Network-dependent actions also depend on phone connectivity. Treat the ring as an additional trigger.

Prove the BLE button-to-SOS path with a development board first. Exercise locked-screen and long-background operation, process death, reboot, airplane mode, reconnection, duplicate/replayed events and actual downstream success/failure reporting on representative phones. Then obtain cell drawings, complete electrical/RF design, verify interference and peak-current margin, and build larger enclosure prototypes before miniaturizing. Validate sweat/contact corrosion, accidental presses, button force, insulation, cell swelling space and charging faults. Establish applicable product/radio/battery approvals with the manufacturer before sale; none are demonstrated by this model.
