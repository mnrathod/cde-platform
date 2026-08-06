import {
  DefaultValueAccessor,
  FormsModule,
  NgControlStatus,
  NgModel
} from "./chunk-RGTUG45E.js";
import {
  ViewerService
} from "./chunk-5US5UH5S.js";
import {
  ActivatedRoute,
  CommonModule,
  Component,
  DatePipe,
  Router,
  ViewChild,
  inject,
  setClassMetadata,
  signal,
  ɵsetClassDebugInfo,
  ɵɵadvance,
  ɵɵclassMap,
  ɵɵconditional,
  ɵɵconditionalCreate,
  ɵɵdefineComponent,
  ɵɵelement,
  ɵɵelementEnd,
  ɵɵelementStart,
  ɵɵgetCurrentView,
  ɵɵlistener,
  ɵɵloadQuery,
  ɵɵnamespaceHTML,
  ɵɵnamespaceSVG,
  ɵɵnextContext,
  ɵɵpipe,
  ɵɵpipeBind2,
  ɵɵproperty,
  ɵɵqueryRefresh,
  ɵɵrepeater,
  ɵɵrepeaterCreate,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵsanitizeHtml,
  ɵɵsanitizeUrl,
  ɵɵtext,
  ɵɵtextInterpolate,
  ɵɵtextInterpolate1,
  ɵɵtextInterpolate2,
  ɵɵtwoWayBindingSet,
  ɵɵtwoWayListener,
  ɵɵtwoWayProperty,
  ɵɵviewQuery
} from "./chunk-Y4NC365O.js";

// src/app/features/viewer/viewer.component.ts
var _c0 = ["viewerContainer"];
var _c1 = ["pdfContainer"];
var _c2 = ["markupOverlay"];
var _forTrack0 = ($index, $item) => $item.id;
function ViewerComponent_For_10_Template(rf, ctx) {
  if (rf & 1) {
    const _r2 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "button", 27);
    \u0275\u0275listener("click", function ViewerComponent_For_10_Template_button_click_0_listener() {
      const t_r3 = \u0275\u0275restoreView(_r2).$implicit;
      const ctx_r3 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r3.setTool(t_r3.id));
    });
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const t_r3 = ctx.$implicit;
    const ctx_r3 = \u0275\u0275nextContext();
    \u0275\u0275classMap(ctx_r3.activeTool() === t_r3.id ? "bg-accent text-white border-accent shadow-sm" : "bg-white text-gray-600 border-gray-300 hover:bg-blue-50 hover:text-accent hover:border-blue-300");
    \u0275\u0275advance();
    \u0275\u0275textInterpolate2(" ", t_r3.icon, " ", t_r3.label, " ");
  }
}
function ViewerComponent_Conditional_22_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 16)(1, "div", 28)(2, "div", 29);
    \u0275\u0275text(3, "\u23F3");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(4, "div", 30);
    \u0275\u0275text(5, "Loading document...");
    \u0275\u0275elementEnd()()();
  }
}
function ViewerComponent_Conditional_23_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 17);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r3 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" \u26A0\uFE0F ", ctx_r3.errorMsg(), " ");
  }
}
function ViewerComponent_Conditional_24_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "div", 18, 3);
  }
  if (rf & 2) {
    const ctx_r3 = \u0275\u0275nextContext();
    \u0275\u0275property("innerHTML", ctx_r3.viewerData().content, \u0275\u0275sanitizeHtml);
  }
}
function ViewerComponent_Conditional_27_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "img", 20);
  }
  if (rf & 2) {
    const ctx_r3 = \u0275\u0275nextContext();
    \u0275\u0275property("src", ctx_r3.imageUrl(), \u0275\u0275sanitizeUrl);
  }
}
function ViewerComponent_For_35_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 25)(1, "div", 31);
    \u0275\u0275text(2);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "div", 32);
    \u0275\u0275text(4);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(5, "div", 33);
    \u0275\u0275text(6);
    \u0275\u0275pipe(7, "date");
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const ann_r5 = ctx.$implicit;
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(ann_r5.authorName);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(ann_r5.comment);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(\u0275\u0275pipeBind2(7, 3, ann_r5.createdAt, "short"));
  }
}
function ViewerComponent_ForEmpty_36_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 26);
    \u0275\u0275text(1, " No annotations yet.");
    \u0275\u0275element(2, "br");
    \u0275\u0275text(3, "Use the toolbar to add markup. ");
    \u0275\u0275elementEnd();
  }
}
var ViewerComponent = class _ViewerComponent {
  container;
  pdfEl;
  overlay;
  route = inject(ActivatedRoute);
  router = inject(Router);
  service = inject(ViewerService);
  viewerData = signal(null, ...ngDevMode ? [{ debugName: "viewerData" }] : (
    /* istanbul ignore next */
    []
  ));
  loading = signal(true, ...ngDevMode ? [{ debugName: "loading" }] : (
    /* istanbul ignore next */
    []
  ));
  errorMsg = signal("", ...ngDevMode ? [{ debugName: "errorMsg" }] : (
    /* istanbul ignore next */
    []
  ));
  annotations = signal([], ...ngDevMode ? [{ debugName: "annotations" }] : (
    /* istanbul ignore next */
    []
  ));
  imageUrl = signal("", ...ngDevMode ? [{ debugName: "imageUrl" }] : (
    /* istanbul ignore next */
    []
  ));
  activeTool = signal("pan", ...ngDevMode ? [{ debugName: "activeTool" }] : (
    /* istanbul ignore next */
    []
  ));
  markupColor = "#ff0000";
  tools = [
    { id: "pan", icon: "\u270B", label: "Pan" },
    { id: "line", icon: "\u2571", label: "Line" },
    { id: "arrow", icon: "\u2192", label: "Arrow" },
    { id: "rect", icon: "\u25A1", label: "Rect" },
    { id: "circle", icon: "\u25CB", label: "Circle" },
    { id: "freehand", icon: "\u270F", label: "Freehand" },
    { id: "cloud", icon: "\u2601", label: "Cloud" },
    { id: "text", icon: "T", label: "Text" },
    { id: "highlight", icon: "\u258C", label: "Highlight" },
    { id: "stamp", icon: "\u{1F535}", label: "Stamp" }
  ];
  ngOnInit() {
    const id = Number(this.route.snapshot.paramMap.get("id"));
    if (!id) {
      this.router.navigate(["/"]);
      return;
    }
    this.service.getViewerData(id).subscribe({
      next: (data) => {
        this.loading.set(false);
        const ct = data?.type;
        if (ct === "svg") {
          this.viewerData.set(data);
        } else if (ct === "pdf" || data instanceof ArrayBuffer) {
          this.renderPdf(data);
        } else if (ct === "error") {
          this.errorMsg.set(data.error || "Unknown error");
        } else {
          this.viewerData.set(data);
        }
      },
      error: (err) => {
        this.loading.set(false);
        this.errorMsg.set("Failed to load document: " + err.message);
      }
    });
    this.service.getAnnotations(id).subscribe((anns) => this.annotations.set(anns));
  }
  async renderPdf(data) {
    if (!window.pdfjsLib) {
      await this.loadScript("https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js");
      window.pdfjsLib.GlobalWorkerOptions.workerSrc = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
    }
    this.viewerData.set({ type: "pdf", name: "Document" });
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
  setTool(t) {
    this.activeTool.set(t);
  }
  undoMarkup() {
  }
  clearMarkup() {
  }
  saveMarkup() {
  }
  exportXfdf() {
    const id = Number(this.route.snapshot.paramMap.get("id"));
    this.service.exportXfdf(id).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "annotations.xfdf";
      a.click();
      URL.revokeObjectURL(url);
    });
  }
  goBack() {
    this.router.navigate(["/"]);
  }
  static \u0275fac = function ViewerComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _ViewerComponent)();
  };
  static \u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _ViewerComponent, selectors: [["app-viewer"]], viewQuery: function ViewerComponent_Query(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275viewQuery(_c0, 5)(_c1, 5)(_c2, 5);
    }
    if (rf & 2) {
      let _t;
      \u0275\u0275queryRefresh(_t = \u0275\u0275loadQuery()) && (ctx.container = _t.first);
      \u0275\u0275queryRefresh(_t = \u0275\u0275loadQuery()) && (ctx.pdfEl = _t.first);
      \u0275\u0275queryRefresh(_t = \u0275\u0275loadQuery()) && (ctx.overlay = _t.first);
    }
  }, decls: 37, vars: 8, consts: [["viewerContainer", ""], ["pdfContainer", ""], ["markupOverlay", ""], ["svgContainer", ""], [1, "fixed", "inset-0", "flex", "flex-col", 2, "background", "#0a0c14", "z-index", "500"], [1, "flex", "items-center", "h-11", "px-3", "gap-2", "flex-shrink-0", "text-white", 2, "background", "var(--nav)", "box-shadow", "0 2px 4px rgba(0,0,0,.15)"], [1, "text-xs", "px-3", "py-1", "rounded", "border", "border-white/30", "bg-white/10", "hover:bg-white/20", "transition-colors", 3, "click"], [1, "text-sm", "font-semibold", "flex-1", "truncate"], [1, "text-xs", "px-3", "py-1", "rounded", "border", "border-white/30", "bg-white/10", "hover:bg-white/20", 3, "click"], [1, "flex", "items-center", "px-3", "py-1", "gap-1", "flex-shrink-0", "flex-wrap", "border-b", 2, "background", "#f8fafc", "border-color", "var(--border)"], [1, "h-7", "px-2.5", "text-xs", "rounded", "border", "transition-all", "flex", "items-center", "gap-1", 3, "class"], [1, "w-px", "h-4", "bg-gray-300", "mx-0.5"], [1, "h-7", "px-2.5", "text-xs", "rounded", "border", "bg-white", "border-gray-300", "hover:bg-gray-50", 3, "click"], ["type", "color", "title", "Color", 1, "h-7", "w-8", "rounded", "border", "border-gray-300", "cursor-pointer", "p-0.5", 3, "ngModelChange", "ngModel"], [1, "flex", "flex-1", "overflow-hidden"], [1, "flex-1", "overflow-auto", "relative", "flex", "items-start", "justify-center", "p-4", 2, "background", "#0a0c14"], [1, "absolute", "inset-0", "flex", "items-center", "justify-center", "text-white/60"], [1, "max-w-md", "mx-auto", "mt-16", "p-6", "bg-red-900/30", "rounded-lg", "border", "border-red-500/30", "text-red-300", "text-sm"], [1, "relative", 2, "transform-origin", "top left", 3, "innerHTML"], ["id", "pdf-container", 1, "space-y-1"], ["alt", "document", 1, "max-w-full", 3, "src"], ["id", "mk-overlay", 1, "absolute", "top-0", "left-0", "pointer-events-none", 2, "width", "100%", "height", "100%"], [1, "w-64", "bg-white", "border-l", "border-gray-200", "flex", "flex-col", "flex-shrink-0"], [1, "p-3", "border-b", "border-gray-200", "font-semibold", "text-sm", "text-gray-700"], [1, "flex-1", "overflow-y-auto", "p-2", "space-y-2"], [1, "bg-gray-50", "rounded", "border-l-3", "border-accent", "p-2", "text-xs", 2, "border-left-width", "3px", "border-left-color", "var(--accent)"], [1, "text-center", "text-gray-400", "text-xs", "py-8"], [1, "h-7", "px-2.5", "text-xs", "rounded", "border", "transition-all", "flex", "items-center", "gap-1", 3, "click"], [1, "text-center"], [1, "text-3xl", "mb-3", "animate-pulse"], [1, "text-sm"], [1, "font-medium", "text-gray-700"], [1, "text-gray-500", "mt-0.5"], [1, "text-gray-400", "mt-1"]], template: function ViewerComponent_Template(rf, ctx) {
    if (rf & 1) {
      const _r1 = \u0275\u0275getCurrentView();
      \u0275\u0275elementStart(0, "div", 4)(1, "div", 5)(2, "button", 6);
      \u0275\u0275listener("click", function ViewerComponent_Template_button_click_2_listener() {
        return ctx.goBack();
      });
      \u0275\u0275text(3, " \u2190 Back ");
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(4, "span", 7);
      \u0275\u0275text(5);
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(6, "button", 8);
      \u0275\u0275listener("click", function ViewerComponent_Template_button_click_6_listener() {
        return ctx.exportXfdf();
      });
      \u0275\u0275text(7, " \u{1F4E4} XFDF ");
      \u0275\u0275elementEnd()();
      \u0275\u0275elementStart(8, "div", 9);
      \u0275\u0275repeaterCreate(9, ViewerComponent_For_10_Template, 2, 4, "button", 10, _forTrack0);
      \u0275\u0275element(11, "div", 11);
      \u0275\u0275elementStart(12, "button", 12);
      \u0275\u0275listener("click", function ViewerComponent_Template_button_click_12_listener() {
        return ctx.undoMarkup();
      });
      \u0275\u0275text(13, "\u21A9");
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(14, "button", 12);
      \u0275\u0275listener("click", function ViewerComponent_Template_button_click_14_listener() {
        return ctx.clearMarkup();
      });
      \u0275\u0275text(15, "\u{1F5D1}");
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(16, "button", 12);
      \u0275\u0275listener("click", function ViewerComponent_Template_button_click_16_listener() {
        return ctx.saveMarkup();
      });
      \u0275\u0275text(17, "\u{1F4BE} Save");
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(18, "input", 13);
      \u0275\u0275twoWayListener("ngModelChange", function ViewerComponent_Template_input_ngModelChange_18_listener($event) {
        \u0275\u0275restoreView(_r1);
        \u0275\u0275twoWayBindingSet(ctx.markupColor, $event) || (ctx.markupColor = $event);
        return \u0275\u0275resetView($event);
      });
      \u0275\u0275elementEnd()();
      \u0275\u0275elementStart(19, "div", 14)(20, "div", 15, 0);
      \u0275\u0275conditionalCreate(22, ViewerComponent_Conditional_22_Template, 6, 0, "div", 16);
      \u0275\u0275conditionalCreate(23, ViewerComponent_Conditional_23_Template, 2, 1, "div", 17);
      \u0275\u0275conditionalCreate(24, ViewerComponent_Conditional_24_Template, 2, 1, "div", 18);
      \u0275\u0275element(25, "div", 19, 1);
      \u0275\u0275conditionalCreate(27, ViewerComponent_Conditional_27_Template, 1, 1, "img", 20);
      \u0275\u0275namespaceSVG();
      \u0275\u0275element(28, "svg", 21, 2);
      \u0275\u0275elementEnd();
      \u0275\u0275namespaceHTML();
      \u0275\u0275elementStart(30, "div", 22)(31, "div", 23);
      \u0275\u0275text(32);
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(33, "div", 24);
      \u0275\u0275repeaterCreate(34, ViewerComponent_For_35_Template, 8, 6, "div", 25, _forTrack0, false, ViewerComponent_ForEmpty_36_Template, 4, 0, "div", 26);
      \u0275\u0275elementEnd()()()();
    }
    if (rf & 2) {
      let tmp_3_0;
      let tmp_8_0;
      let tmp_9_0;
      \u0275\u0275advance(5);
      \u0275\u0275textInterpolate(((tmp_3_0 = ctx.viewerData()) == null ? null : tmp_3_0.name) || "Loading...");
      \u0275\u0275advance(4);
      \u0275\u0275repeater(ctx.tools);
      \u0275\u0275advance(9);
      \u0275\u0275twoWayProperty("ngModel", ctx.markupColor);
      \u0275\u0275advance(4);
      \u0275\u0275conditional(ctx.loading() ? 22 : -1);
      \u0275\u0275advance();
      \u0275\u0275conditional(ctx.errorMsg() ? 23 : -1);
      \u0275\u0275advance();
      \u0275\u0275conditional(((tmp_8_0 = ctx.viewerData()) == null ? null : tmp_8_0.type) === "svg" && ((tmp_8_0 = ctx.viewerData()) == null ? null : tmp_8_0.content) ? 24 : -1);
      \u0275\u0275advance(3);
      \u0275\u0275conditional(((tmp_9_0 = ctx.viewerData()) == null ? null : tmp_9_0.type) === "image" ? 27 : -1);
      \u0275\u0275advance(5);
      \u0275\u0275textInterpolate1(" Annotations (", ctx.annotations().length, ") ");
      \u0275\u0275advance(2);
      \u0275\u0275repeater(ctx.annotations());
    }
  }, dependencies: [CommonModule, FormsModule, DefaultValueAccessor, NgControlStatus, NgModel, DatePipe], encapsulation: 2 });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(ViewerComponent, [{
    type: Component,
    args: [{
      selector: "app-viewer",
      standalone: true,
      imports: [CommonModule, FormsModule],
      template: `
    <div class="fixed inset-0 flex flex-col" style="background:#0a0c14;z-index:500">

      <!-- Top bar (Asite navy) -->
      <div class="flex items-center h-11 px-3 gap-2 flex-shrink-0 text-white"
           style="background:var(--nav);box-shadow:0 2px 4px rgba(0,0,0,.15)">
        <button (click)="goBack()"
          class="text-xs px-3 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20 transition-colors">
          \u2190 Back
        </button>
        <span class="text-sm font-semibold flex-1 truncate">{{ viewerData()?.name || 'Loading...' }}</span>
        <button (click)="exportXfdf()"
          class="text-xs px-3 py-1 rounded border border-white/30 bg-white/10 hover:bg-white/20">
          \u{1F4E4} XFDF
        </button>
      </div>

      <!-- Markup toolbar -->
      <div class="flex items-center px-3 py-1 gap-1 flex-shrink-0 flex-wrap border-b"
           style="background:#f8fafc;border-color:var(--border)">
        @for (t of tools; track t.id) {
          <button (click)="setTool(t.id)"
            class="h-7 px-2.5 text-xs rounded border transition-all flex items-center gap-1"
            [class]="activeTool() === t.id
              ? 'bg-accent text-white border-accent shadow-sm'
              : 'bg-white text-gray-600 border-gray-300 hover:bg-blue-50 hover:text-accent hover:border-blue-300'">
            {{ t.icon }} {{ t.label }}
          </button>
        }
        <div class="w-px h-4 bg-gray-300 mx-0.5"></div>
        <button (click)="undoMarkup()" class="h-7 px-2.5 text-xs rounded border bg-white border-gray-300 hover:bg-gray-50">\u21A9</button>
        <button (click)="clearMarkup()" class="h-7 px-2.5 text-xs rounded border bg-white border-gray-300 hover:bg-gray-50">\u{1F5D1}</button>
        <button (click)="saveMarkup()" class="h-7 px-2.5 text-xs rounded border bg-white border-gray-300 hover:bg-gray-50">\u{1F4BE} Save</button>
        <input type="color" [(ngModel)]="markupColor" title="Color"
          class="h-7 w-8 rounded border border-gray-300 cursor-pointer p-0.5" />
      </div>

      <!-- Viewer canvas area + sidebar -->
      <div class="flex flex-1 overflow-hidden">

        <!-- Main canvas -->
        <div #viewerContainer class="flex-1 overflow-auto relative flex items-start justify-center p-4"
             style="background:#0a0c14">

          @if (loading()) {
            <div class="absolute inset-0 flex items-center justify-center text-white/60">
              <div class="text-center">
                <div class="text-3xl mb-3 animate-pulse">\u23F3</div>
                <div class="text-sm">Loading document...</div>
              </div>
            </div>
          }

          @if (errorMsg()) {
            <div class="max-w-md mx-auto mt-16 p-6 bg-red-900/30 rounded-lg border border-red-500/30 text-red-300 text-sm">
              \u26A0\uFE0F {{ errorMsg() }}
            </div>
          }

          <!-- SVG viewer -->
          @if (viewerData()?.type === 'svg' && viewerData()?.content) {
            <div #svgContainer class="relative" style="transform-origin:top left"
                 [innerHTML]="viewerData()!.content!"></div>
          }

          <!-- PDF canvas container -->
          <div #pdfContainer id="pdf-container" class="space-y-1"></div>

          <!-- Image viewer -->
          @if (viewerData()?.type === 'image') {
            <img [src]="imageUrl()" class="max-w-full" alt="document" />
          }

          <!-- SVG markup overlay -->
          <svg #markupOverlay id="mk-overlay"
            class="absolute top-0 left-0 pointer-events-none"
            style="width:100%;height:100%">
          </svg>
        </div>

        <!-- Annotation sidebar -->
        <div class="w-64 bg-white border-l border-gray-200 flex flex-col flex-shrink-0">
          <div class="p-3 border-b border-gray-200 font-semibold text-sm text-gray-700">
            Annotations ({{ annotations().length }})
          </div>
          <div class="flex-1 overflow-y-auto p-2 space-y-2">
            @for (ann of annotations(); track ann.id) {
              <div class="bg-gray-50 rounded border-l-3 border-accent p-2 text-xs"
                   style="border-left-width:3px;border-left-color:var(--accent)">
                <div class="font-medium text-gray-700">{{ ann.authorName }}</div>
                <div class="text-gray-500 mt-0.5">{{ ann.comment }}</div>
                <div class="text-gray-400 mt-1">{{ ann.createdAt | date:'short' }}</div>
              </div>
            } @empty {
              <div class="text-center text-gray-400 text-xs py-8">
                No annotations yet.<br>Use the toolbar to add markup.
              </div>
            }
          </div>
        </div>
      </div>
    </div>
  `
    }]
  }], null, { container: [{
    type: ViewChild,
    args: ["viewerContainer"]
  }], pdfEl: [{
    type: ViewChild,
    args: ["pdfContainer"]
  }], overlay: [{
    type: ViewChild,
    args: ["markupOverlay"]
  }] });
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(ViewerComponent, { className: "ViewerComponent", filePath: "src/app/features/viewer/viewer.component.ts", lineNumber: 118 });
})();
export {
  ViewerComponent
};
//# sourceMappingURL=chunk-O72TJYGT.js.map
