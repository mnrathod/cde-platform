import {
  ViewerService
} from "./chunk-5US5UH5S.js";
import {
  ActivatedRoute,
  CommonModule,
  Component,
  Router,
  ViewChild,
  inject,
  setClassMetadata,
  signal,
  ɵsetClassDebugInfo,
  ɵɵadvance,
  ɵɵclassProp,
  ɵɵconditional,
  ɵɵconditionalCreate,
  ɵɵdefineComponent,
  ɵɵdomElement,
  ɵɵdomElementEnd,
  ɵɵdomElementStart,
  ɵɵdomListener,
  ɵɵgetCurrentView,
  ɵɵloadQuery,
  ɵɵnextContext,
  ɵɵqueryRefresh,
  ɵɵrepeater,
  ɵɵrepeaterCreate,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵstyleProp,
  ɵɵtext,
  ɵɵtextInterpolate,
  ɵɵtextInterpolate1,
  ɵɵviewQuery
} from "./chunk-Y4NC365O.js";

// src/app/features/viewer/viewer3d/viewer3d.component.ts
var _c0 = ["threeCanvas"];
var _c1 = ["canvasWrap"];
var _forTrack0 = ($index, $item) => $item.label;
var _forTrack1 = ($index, $item) => $item.name;
function Viewer3dComponent_Conditional_19_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 9);
    \u0275\u0275domElement(1, "div", 18);
    \u0275\u0275domElementStart(2, "div", 19);
    \u0275\u0275text(3);
    \u0275\u0275domElementEnd()();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(3);
    \u0275\u0275textInterpolate(ctx_r0.loadingMsg());
  }
}
function Viewer3dComponent_Conditional_20_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 10)(1, "div", 20);
    \u0275\u0275text(2);
    \u0275\u0275domElementEnd()();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate1(" \u26A0\uFE0F ", ctx_r0.errorMsg(), " ");
  }
}
function Viewer3dComponent_For_28_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275domElementStart(0, "div", 15)(1, "span", 21);
    \u0275\u0275text(2);
    \u0275\u0275domElementEnd();
    \u0275\u0275domElementStart(3, "span", 22);
    \u0275\u0275text(4);
    \u0275\u0275domElementEnd()();
  }
  if (rf & 2) {
    const stat_r2 = ctx.$implicit;
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(stat_r2.label);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(stat_r2.value);
  }
}
function Viewer3dComponent_For_33_Template(rf, ctx) {
  if (rf & 1) {
    const _r3 = \u0275\u0275getCurrentView();
    \u0275\u0275domElementStart(0, "div", 23);
    \u0275\u0275domListener("click", function Viewer3dComponent_For_33_Template_div_click_0_listener() {
      const layer_r4 = \u0275\u0275restoreView(_r3).$implicit;
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.toggleLayer(layer_r4));
    });
    \u0275\u0275domElement(1, "div", 24);
    \u0275\u0275domElementStart(2, "span", 21);
    \u0275\u0275text(3);
    \u0275\u0275domElementEnd()();
  }
  if (rf & 2) {
    const layer_r4 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275styleProp("background", layer_r4.color);
    \u0275\u0275advance();
    \u0275\u0275classProp("line-through", !layer_r4.visible);
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" ", layer_r4.name, " ");
  }
}
var Viewer3dComponent = class _Viewer3dComponent {
  canvas;
  wrap;
  route = inject(ActivatedRoute);
  router = inject(Router);
  service = inject(ViewerService);
  title = signal("3D Model", ...ngDevMode ? [{ debugName: "title" }] : (
    /* istanbul ignore next */
    []
  ));
  loading = signal(true, ...ngDevMode ? [{ debugName: "loading" }] : (
    /* istanbul ignore next */
    []
  ));
  loadingMsg = signal("Loading Three.js...", ...ngDevMode ? [{ debugName: "loadingMsg" }] : (
    /* istanbul ignore next */
    []
  ));
  errorMsg = signal("", ...ngDevMode ? [{ debugName: "errorMsg" }] : (
    /* istanbul ignore next */
    []
  ));
  wireframe = signal(false, ...ngDevMode ? [{ debugName: "wireframe" }] : (
    /* istanbul ignore next */
    []
  ));
  stats = signal([], ...ngDevMode ? [{ debugName: "stats" }] : (
    /* istanbul ignore next */
    []
  ));
  layers = signal([], ...ngDevMode ? [{ debugName: "layers" }] : (
    /* istanbul ignore next */
    []
  ));
  three = null;
  animId = null;
  IFC_COLORS = {
    IfcWall: [0.85, 0.82, 0.78],
    IfcWallStandardCase: [0.85, 0.82, 0.78],
    IfcSlab: [0.75, 0.75, 0.75],
    IfcRoof: [0.62, 0.45, 0.35],
    IfcColumn: [0.8, 0.75, 0.7],
    IfcBeam: [0.7, 0.65, 0.6],
    IfcDoor: [0.65, 0.45, 0.25],
    IfcWindow: [0.55, 0.75, 0.9],
    IfcStair: [0.8, 0.78, 0.75],
    IfcFurnishingElement: [0.6, 0.5, 0.4]
  };
  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get("id"));
    this.loadModel(id);
  }
  ngOnDestroy() {
    if (this.animId)
      cancelAnimationFrame(this.animId);
    this.three?.renderer?.dispose();
  }
  async loadModel(id) {
    await this.loadThreeJS();
    this.loadingMsg.set("Fetching model data...");
    this.service.get3DData(id).subscribe({
      next: (data) => {
        if (data?.type === "ifc3d") {
          this.loadingMsg.set("Building 3D scene...");
          setTimeout(() => this.buildIFCScene(data), 50);
        } else if (data?.type === "revit_binary") {
          this.loading.set(false);
          this.errorMsg.set("Revit binary file \u2014 export to IFC first.\nFile \u2192 Export \u2192 IFC in Revit");
        } else if (data?.success === false) {
          this.loading.set(false);
          this.errorMsg.set(data.error || "Conversion failed");
        } else {
          this.loading.set(false);
          this.errorMsg.set("Unsupported 3D format");
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMsg.set(err.message);
      }
    });
  }
  async loadThreeJS() {
    if (window.THREE)
      return;
    await this.loadScript("https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js");
    await this.loadScript("https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/OrbitControls.js");
  }
  buildIFCScene(data) {
    const T = window.THREE;
    const canvas = this.canvas.nativeElement;
    const W = this.wrap.nativeElement.clientWidth - 208;
    const H = this.wrap.nativeElement.clientHeight;
    const renderer = new T.WebGLRenderer({ canvas, antialias: true });
    renderer.setSize(W, H);
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    renderer.setClearColor(1250591);
    const scene = new T.Scene();
    const camera = new T.PerspectiveCamera(45, W / H, 0.01, 1e5);
    camera.position.set(20, 15, 20);
    scene.add(new T.AmbientLight(16777215, 0.6));
    const dir = new T.DirectionalLight(16777215, 0.8);
    dir.position.set(50, 100, 50);
    scene.add(dir);
    const controls = new T.OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    const grid = new T.GridHelper(100, 20, 3355460, 2236979);
    scene.add(grid);
    const gd = data.gltfData;
    const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0)).buffer;
    const geo = new T.BufferGeometry();
    geo.setAttribute("position", new T.BufferAttribute(new Float32Array(b64(gd.positions)), 3));
    geo.setAttribute("normal", new T.BufferAttribute(new Float32Array(b64(gd.normals)), 3));
    geo.setAttribute("color", new T.BufferAttribute(new Float32Array(b64(gd.colors)), 3));
    geo.setIndex(new T.BufferAttribute(new Uint32Array(b64(gd.indices)), 1));
    const mat = new T.MeshPhongMaterial({ vertexColors: true, side: T.DoubleSide, shininess: 30 });
    const mesh = new T.Mesh(geo, mat);
    scene.add(mesh);
    const box = new T.Box3().expandByObject(mesh);
    const center = box.getCenter(new T.Vector3());
    const size = box.getSize(new T.Vector3());
    const maxDim = Math.max(size.x, size.y, size.z);
    camera.position.set(center.x + maxDim * 1.2, center.y + maxDim * 0.8, center.z + maxDim * 1.2);
    controls.target.copy(center);
    camera.near = maxDim * 1e-3;
    camera.far = maxDim * 100;
    camera.updateProjectionMatrix();
    grid.scale.setScalar(maxDim / 10);
    grid.position.y = box.min.y;
    this.three = { renderer, scene, camera, controls, mesh, wireframe: false };
    this.loading.set(false);
    this.stats.set([
      { label: "Elements", value: gd.elementCount.toLocaleString() },
      { label: "Triangles", value: gd.triangleCount.toLocaleString() },
      { label: "Schema", value: gd.schema }
    ]);
    this.layers.set(Object.entries(this.IFC_COLORS).map(([name, rgb]) => ({
      name: name.replace("Ifc", ""),
      color: `rgb(${rgb.map((v) => Math.round(v * 255)).join(",")})`,
      visible: true
    })));
    const animate = () => {
      this.animId = requestAnimationFrame(animate);
      controls.update();
      renderer.render(scene, camera);
    };
    animate();
    window.addEventListener("resize", () => {
      const W2 = this.wrap.nativeElement.clientWidth - 208;
      const H2 = this.wrap.nativeElement.clientHeight;
      camera.aspect = W2 / H2;
      camera.updateProjectionMatrix();
      renderer.setSize(W2, H2);
    });
  }
  resetCamera() {
  }
  toggleWireframe() {
    if (!this.three)
      return;
    this.wireframe.update((w) => !w);
    this.three.mesh.material.wireframe = this.wireframe();
  }
  snapView(view) {
  }
  toggleLayer(layer) {
    layer.visible = !layer.visible;
  }
  loadScript(src) {
    return new Promise((res, rej) => {
      const s = document.createElement("script");
      s.src = src;
      s.onload = () => res();
      s.onerror = rej;
      document.head.appendChild(s);
    });
  }
  goBack() {
    this.router.navigate(["/"]);
  }
  static \u0275fac = function Viewer3dComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _Viewer3dComponent)();
  };
  static \u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _Viewer3dComponent, selectors: [["app-viewer3d"]], viewQuery: function Viewer3dComponent_Query(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275viewQuery(_c0, 5)(_c1, 5);
    }
    if (rf & 2) {
      let _t;
      \u0275\u0275queryRefresh(_t = \u0275\u0275loadQuery()) && (ctx.canvas = _t.first);
      \u0275\u0275queryRefresh(_t = \u0275\u0275loadQuery()) && (ctx.wrap = _t.first);
    }
  }, decls: 34, vars: 5, consts: [["canvasWrap", ""], ["threeCanvas", ""], [1, "fixed", "inset-0", "flex", "flex-col", 2, "background", "#0a0c14", "z-index", "500"], [1, "flex", "items-center", "h-11", "px-3", "gap-2", "flex-shrink-0", "text-white", "flex-wrap", 2, "background", "var(--nav)", "box-shadow", "0 2px 4px rgba(0,0,0,.15)"], [1, "text-xs", "px-3", "py-1", "rounded", "border", "border-white/30", "bg-white/10", "hover:bg-white/20", 3, "click"], [1, "text-sm", "font-semibold", "flex-1", "truncate"], [1, "text-xs", "px-2", "py-1", "rounded", "border", "border-white/30", "bg-white/10", "hover:bg-white/20", 3, "click"], [1, "flex", "flex-1", "overflow-hidden", "relative"], [1, "flex-1", "relative", "overflow-hidden"], [1, "absolute", "inset-0", "flex", "flex-col", "items-center", "justify-center", "text-white/60", "z-10", 2, "background", "#0a0c14"], [1, "absolute", "inset-0", "flex", "items-center", "justify-center", "z-10"], [1, "block", "w-full", "h-full"], [1, "w-52", "bg-white", "border-l", "border-gray-200", "flex", "flex-col", "flex-shrink-0"], [1, "p-3", "border-b", "border-gray-200"], [1, "text-xs", "font-semibold", "text-gray-500", "uppercase", "tracking-wider", "mb-2"], [1, "flex", "justify-between", "text-xs", "py-1", "border-b", "border-gray-100"], [1, "p-3", "flex-1"], [1, "flex", "items-center", "gap-2", "py-1", "text-xs", "cursor-pointer", "hover:bg-gray-50", "rounded", "px-1"], [1, "w-10", "h-10", "border-3", "border-white/20", "border-t-accent", "rounded-full", "animate-spin", "mb-4", 2, "border-width", "3px"], [1, "text-sm"], [1, "max-w-md", "p-6", "bg-red-900/30", "rounded-lg", "border", "border-red-500/30", "text-red-300", "text-sm", "whitespace-pre-wrap"], [1, "text-gray-600"], [1, "font-mono", "font-semibold", "text-green-600"], [1, "flex", "items-center", "gap-2", "py-1", "text-xs", "cursor-pointer", "hover:bg-gray-50", "rounded", "px-1", 3, "click"], [1, "w-3", "h-3", "rounded", "flex-shrink-0"]], template: function Viewer3dComponent_Template(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275domElementStart(0, "div", 2)(1, "div", 3)(2, "button", 4);
      \u0275\u0275domListener("click", function Viewer3dComponent_Template_button_click_2_listener() {
        return ctx.goBack();
      });
      \u0275\u0275text(3, "\u2190 Back");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(4, "span", 5);
      \u0275\u0275text(5);
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(6, "button", 6);
      \u0275\u0275domListener("click", function Viewer3dComponent_Template_button_click_6_listener() {
        return ctx.resetCamera();
      });
      \u0275\u0275text(7, "\u2302 Reset");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(8, "button", 6);
      \u0275\u0275domListener("click", function Viewer3dComponent_Template_button_click_8_listener() {
        return ctx.toggleWireframe();
      });
      \u0275\u0275text(9, "\u2B21 Wire");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(10, "button", 6);
      \u0275\u0275domListener("click", function Viewer3dComponent_Template_button_click_10_listener() {
        return ctx.snapView("top");
      });
      \u0275\u0275text(11, "\u22A4 Top");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(12, "button", 6);
      \u0275\u0275domListener("click", function Viewer3dComponent_Template_button_click_12_listener() {
        return ctx.snapView("front");
      });
      \u0275\u0275text(13, "\u25EB Front");
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(14, "button", 6);
      \u0275\u0275domListener("click", function Viewer3dComponent_Template_button_click_14_listener() {
        return ctx.snapView("side");
      });
      \u0275\u0275text(15, "\u25E7 Side");
      \u0275\u0275domElementEnd()();
      \u0275\u0275domElementStart(16, "div", 7)(17, "div", 8, 0);
      \u0275\u0275conditionalCreate(19, Viewer3dComponent_Conditional_19_Template, 4, 1, "div", 9);
      \u0275\u0275conditionalCreate(20, Viewer3dComponent_Conditional_20_Template, 3, 1, "div", 10);
      \u0275\u0275domElement(21, "canvas", 11, 1);
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(23, "div", 12)(24, "div", 13)(25, "div", 14);
      \u0275\u0275text(26, "Model Info");
      \u0275\u0275domElementEnd();
      \u0275\u0275repeaterCreate(27, Viewer3dComponent_For_28_Template, 5, 2, "div", 15, _forTrack0);
      \u0275\u0275domElementEnd();
      \u0275\u0275domElementStart(29, "div", 16)(30, "div", 14);
      \u0275\u0275text(31, "Element Types");
      \u0275\u0275domElementEnd();
      \u0275\u0275repeaterCreate(32, Viewer3dComponent_For_33_Template, 4, 5, "div", 17, _forTrack1);
      \u0275\u0275domElementEnd()()()();
    }
    if (rf & 2) {
      \u0275\u0275advance(5);
      \u0275\u0275textInterpolate(ctx.title());
      \u0275\u0275advance(3);
      \u0275\u0275classProp("bg-accent", ctx.wireframe());
      \u0275\u0275advance(11);
      \u0275\u0275conditional(ctx.loading() ? 19 : -1);
      \u0275\u0275advance();
      \u0275\u0275conditional(ctx.errorMsg() ? 20 : -1);
      \u0275\u0275advance(7);
      \u0275\u0275repeater(ctx.stats());
      \u0275\u0275advance(5);
      \u0275\u0275repeater(ctx.layers());
    }
  }, dependencies: [CommonModule], encapsulation: 2 });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(Viewer3dComponent, [{
    type: Component,
    args: [{
      selector: "app-viewer3d",
      standalone: true,
      imports: [CommonModule],
      template: `
    <div class="fixed inset-0 flex flex-col" style="background:#0a0c14;z-index:500">

      <!-- Top bar -->
      <div class="flex items-center h-11 px-3 gap-2 flex-shrink-0 text-white flex-wrap"
           style="background:var(--nav);box-shadow:0 2px 4px rgba(0,0,0,.15)">
        <button (click)="goBack()"
          class="text-xs px-3 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20">\u2190 Back</button>
        <span class="text-sm font-semibold flex-1 truncate">{{ title() }}</span>
        <button (click)="resetCamera()" class="text-xs px-2 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20">\u2302 Reset</button>
        <button (click)="toggleWireframe()" class="text-xs px-2 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20"
          [class.bg-accent]="wireframe()">\u2B21 Wire</button>
        <button (click)="snapView('top')"   class="text-xs px-2 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20">\u22A4 Top</button>
        <button (click)="snapView('front')" class="text-xs px-2 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20">\u25EB Front</button>
        <button (click)="snapView('side')"  class="text-xs px-2 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20">\u25E7 Side</button>
      </div>

      <!-- Body -->
      <div class="flex flex-1 overflow-hidden relative">

        <!-- Canvas wrap -->
        <div #canvasWrap class="flex-1 relative overflow-hidden">
          @if (loading()) {
            <div class="absolute inset-0 flex flex-col items-center justify-center text-white/60 z-10"
                 style="background:#0a0c14">
              <div class="w-10 h-10 border-3 border-white/20 border-t-accent rounded-full animate-spin mb-4"
                   style="border-width:3px"></div>
              <div class="text-sm">{{ loadingMsg() }}</div>
            </div>
          }
          @if (errorMsg()) {
            <div class="absolute inset-0 flex items-center justify-center z-10">
              <div class="max-w-md p-6 bg-red-900/30 rounded-lg border border-red-500/30 text-red-300 text-sm whitespace-pre-wrap">
                \u26A0\uFE0F {{ errorMsg() }}
              </div>
            </div>
          }
          <canvas #threeCanvas class="block w-full h-full"></canvas>
        </div>

        <!-- Sidebar -->
        <div class="w-52 bg-white border-l border-gray-200 flex flex-col flex-shrink-0">
          <div class="p-3 border-b border-gray-200">
            <div class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Model Info</div>
            @for (stat of stats(); track stat.label) {
              <div class="flex justify-between text-xs py-1 border-b border-gray-100">
                <span class="text-gray-600">{{ stat.label }}</span>
                <span class="font-mono font-semibold text-green-600">{{ stat.value }}</span>
              </div>
            }
          </div>
          <div class="p-3 flex-1">
            <div class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Element Types</div>
            @for (layer of layers(); track layer.name) {
              <div class="flex items-center gap-2 py-1 text-xs cursor-pointer hover:bg-gray-50 rounded px-1"
                   (click)="toggleLayer(layer)">
                <div class="w-3 h-3 rounded flex-shrink-0"
                     [style.background]="layer.color"></div>
                <span [class.line-through]="!layer.visible" class="text-gray-600">
                  {{ layer.name }}
                </span>
              </div>
            }
          </div>
        </div>
      </div>
    </div>
  `
    }]
  }], null, { canvas: [{
    type: ViewChild,
    args: ["threeCanvas"]
  }], wrap: [{
    type: ViewChild,
    args: ["canvasWrap"]
  }] });
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(Viewer3dComponent, { className: "Viewer3dComponent", filePath: "src/app/features/viewer/viewer3d/viewer3d.component.ts", lineNumber: 79 });
})();
export {
  Viewer3dComponent
};
//# sourceMappingURL=chunk-HXKZ554R.js.map
