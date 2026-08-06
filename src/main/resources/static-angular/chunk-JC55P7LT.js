import {
  AuthService
} from "./chunk-F5EIFQ4X.js";
import {
  DefaultValueAccessor,
  FormsModule,
  NgControlStatus,
  NgModel,
  NgSelectOption,
  SelectControlValueAccessor,
  ɵNgSelectMultipleOption
} from "./chunk-RGTUG45E.js";
import {
  DocumentService,
  ProjectService
} from "./chunk-B7Q533N4.js";
import {
  CommonModule,
  Component,
  Router,
  RouterOutlet,
  effect,
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
  ɵɵnextContext,
  ɵɵproperty,
  ɵɵreference,
  ɵɵrepeater,
  ɵɵrepeaterCreate,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵstyleMap,
  ɵɵtext,
  ɵɵtextInterpolate,
  ɵɵtextInterpolate1,
  ɵɵtextInterpolate2,
  ɵɵtwoWayBindingSet,
  ɵɵtwoWayListener,
  ɵɵtwoWayProperty
} from "./chunk-Y4NC365O.js";

// src/app/features/projects/shell.component.ts
var _forTrack0 = ($index, $item) => $item.id;
function ShellComponent_For_22_Template(rf, ctx) {
  if (rf & 1) {
    const _r1 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "div", 25);
    \u0275\u0275listener("click", function ShellComponent_For_22_Template_div_click_0_listener() {
      const p_r2 = \u0275\u0275restoreView(_r1).$implicit;
      const ctx_r2 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r2.selectProject(p_r2));
    });
    \u0275\u0275elementStart(1, "div", 26);
    \u0275\u0275text(2);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "div", 27)(4, "span", 28);
    \u0275\u0275text(5);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(6, "span", 29);
    \u0275\u0275text(7);
    \u0275\u0275elementEnd()()();
  }
  if (rf & 2) {
    let tmp_10_0;
    const p_r2 = ctx.$implicit;
    const ctx_r2 = \u0275\u0275nextContext();
    \u0275\u0275classMap(((tmp_10_0 = ctx_r2.selectedProject()) == null ? null : tmp_10_0.id) === p_r2.id ? "bg-blue-50 border border-blue-200 text-accent" : "hover:bg-gray-50 text-gray-700");
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(p_r2.name);
    \u0275\u0275advance(2);
    \u0275\u0275styleMap(ctx_r2.phaseStyle(p_r2.phase));
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(p_r2.phase);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate1("", p_r2.documentCount || 0, " docs");
  }
}
function ShellComponent_Conditional_28_Template(rf, ctx) {
  if (rf & 1) {
    const _r4 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "div", 3)(1, "button", 30);
    \u0275\u0275listener("click", function ShellComponent_Conditional_28_Template_button_click_1_listener() {
      \u0275\u0275restoreView(_r4);
      const ctx_r2 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r2.openCompare());
    });
    \u0275\u0275text(2, " \u{1F50D} Compare ");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "button", 31);
    \u0275\u0275listener("click", function ShellComponent_Conditional_28_Template_button_click_3_listener() {
      \u0275\u0275restoreView(_r4);
      const ctx_r2 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r2.showUpload.set(true));
    });
    \u0275\u0275text(4, " \u{1F4E4} Upload ");
    \u0275\u0275elementEnd()();
  }
}
function ShellComponent_Conditional_30_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 20)(1, "div", 32);
    \u0275\u0275text(2, "\u{1F4C1}");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "div", 33);
    \u0275\u0275text(4, "Select a project to see its documents");
    \u0275\u0275elementEnd()();
  }
}
function ShellComponent_Conditional_31_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 21);
    \u0275\u0275text(1, "Loading...");
    \u0275\u0275elementEnd();
  }
}
function ShellComponent_Conditional_32_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 22)(1, "div", 34);
    \u0275\u0275text(2, "\u{1F4C4}");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "div", 33);
    \u0275\u0275text(4, "No documents yet. Click Upload to add files.");
    \u0275\u0275elementEnd()();
  }
}
function ShellComponent_Conditional_33_For_2_Template(rf, ctx) {
  if (rf & 1) {
    const _r5 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "div", 36);
    \u0275\u0275listener("click", function ShellComponent_Conditional_33_For_2_Template_div_click_0_listener() {
      const doc_r6 = \u0275\u0275restoreView(_r5).$implicit;
      const ctx_r2 = \u0275\u0275nextContext(2);
      return \u0275\u0275resetView(ctx_r2.openDocument(doc_r6));
    });
    \u0275\u0275elementStart(1, "div", 37);
    \u0275\u0275text(2);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "div", 38)(4, "div", 39);
    \u0275\u0275text(5);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(6, "div", 40);
    \u0275\u0275text(7);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(8, "div", 41)(9, "span", 28);
    \u0275\u0275text(10);
    \u0275\u0275elementEnd()()()();
  }
  if (rf & 2) {
    const doc_r6 = ctx.$implicit;
    const ctx_r2 = \u0275\u0275nextContext(2);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate1(" ", ctx_r2.documentService.getFileIcon(doc_r6), " ");
    \u0275\u0275advance(3);
    \u0275\u0275textInterpolate(doc_r6.name);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate2(" ", doc_r6.drawingNumber || doc_r6.documentType, "", doc_r6.revision ? " \xB7 Rev " + doc_r6.revision : "", " ");
    \u0275\u0275advance(2);
    \u0275\u0275styleMap(ctx_r2.statusStyle(doc_r6.status));
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(doc_r6.status.replace("_", " "));
  }
}
function ShellComponent_Conditional_33_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 23);
    \u0275\u0275repeaterCreate(1, ShellComponent_Conditional_33_For_2_Template, 11, 7, "div", 35, _forTrack0);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r2 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275repeater(ctx_r2.documentService.documents());
  }
}
function ShellComponent_Conditional_34_Conditional_7_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 46);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r2 = \u0275\u0275nextContext(2);
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1("\u{1F4CE} ", ctx_r2.selectedFile().name);
  }
}
function ShellComponent_Conditional_34_Conditional_8_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275text(0, " Click to browse or drag & drop ");
  }
}
function ShellComponent_Conditional_34_Template(rf, ctx) {
  if (rf & 1) {
    const _r7 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "div", 24)(1, "div", 42)(2, "h3", 43);
    \u0275\u0275text(3, "\u{1F4C2} Upload Document");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(4, "div", 44);
    \u0275\u0275listener("click", function ShellComponent_Conditional_34_Template_div_click_4_listener() {
      \u0275\u0275restoreView(_r7);
      const fileInput_r8 = \u0275\u0275reference(10);
      return \u0275\u0275resetView(fileInput_r8.click());
    })("dragover", function ShellComponent_Conditional_34_Template_div_dragover_4_listener($event) {
      return $event.preventDefault();
    })("drop", function ShellComponent_Conditional_34_Template_div_drop_4_listener($event) {
      \u0275\u0275restoreView(_r7);
      const ctx_r2 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r2.onDrop($event));
    });
    \u0275\u0275elementStart(5, "div", 45);
    \u0275\u0275text(6, "\u{1F4C4}");
    \u0275\u0275elementEnd();
    \u0275\u0275conditionalCreate(7, ShellComponent_Conditional_34_Conditional_7_Template, 2, 1, "div", 46)(8, ShellComponent_Conditional_34_Conditional_8_Template, 1, 0);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(9, "input", 47, 0);
    \u0275\u0275listener("change", function ShellComponent_Conditional_34_Template_input_change_9_listener($event) {
      \u0275\u0275restoreView(_r7);
      const ctx_r2 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r2.onFileSelect($event));
    });
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(11, "div", 48)(12, "div")(13, "label", 49);
    \u0275\u0275text(14, "Document Name *");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(15, "input", 50);
    \u0275\u0275twoWayListener("ngModelChange", function ShellComponent_Conditional_34_Template_input_ngModelChange_15_listener($event) {
      \u0275\u0275restoreView(_r7);
      const ctx_r2 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r2.uploadMeta.name, $event) || (ctx_r2.uploadMeta.name = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(16, "div", 51)(17, "div")(18, "label", 49);
    \u0275\u0275text(19, "Type");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(20, "select", 52);
    \u0275\u0275twoWayListener("ngModelChange", function ShellComponent_Conditional_34_Template_select_ngModelChange_20_listener($event) {
      \u0275\u0275restoreView(_r7);
      const ctx_r2 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r2.uploadMeta.documentType, $event) || (ctx_r2.uploadMeta.documentType = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementStart(21, "option", 53);
    \u0275\u0275text(22, "BIM Model");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(23, "option", 54);
    \u0275\u0275text(24, "Drawing");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(25, "option", 55);
    \u0275\u0275text(26, "Specification");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(27, "option", 56);
    \u0275\u0275text(28, "Report");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(29, "option", 57);
    \u0275\u0275text(30, "Schedule");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(31, "option", 58);
    \u0275\u0275text(32, "Other");
    \u0275\u0275elementEnd()()();
    \u0275\u0275elementStart(33, "div")(34, "label", 49);
    \u0275\u0275text(35, "Revision");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(36, "input", 59);
    \u0275\u0275twoWayListener("ngModelChange", function ShellComponent_Conditional_34_Template_input_ngModelChange_36_listener($event) {
      \u0275\u0275restoreView(_r7);
      const ctx_r2 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r2.uploadMeta.revision, $event) || (ctx_r2.uploadMeta.revision = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()()()();
    \u0275\u0275elementStart(37, "div", 60)(38, "button", 61);
    \u0275\u0275listener("click", function ShellComponent_Conditional_34_Template_button_click_38_listener() {
      \u0275\u0275restoreView(_r7);
      const ctx_r2 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r2.closeUpload());
    });
    \u0275\u0275text(39, "Cancel");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(40, "button", 62);
    \u0275\u0275listener("click", function ShellComponent_Conditional_34_Template_button_click_40_listener() {
      \u0275\u0275restoreView(_r7);
      const ctx_r2 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r2.doUpload());
    });
    \u0275\u0275text(41);
    \u0275\u0275elementEnd()()()();
  }
  if (rf & 2) {
    const ctx_r2 = \u0275\u0275nextContext();
    \u0275\u0275advance(7);
    \u0275\u0275conditional(ctx_r2.selectedFile() ? 7 : 8);
    \u0275\u0275advance(8);
    \u0275\u0275twoWayProperty("ngModel", ctx_r2.uploadMeta.name);
    \u0275\u0275advance(5);
    \u0275\u0275twoWayProperty("ngModel", ctx_r2.uploadMeta.documentType);
    \u0275\u0275advance(16);
    \u0275\u0275twoWayProperty("ngModel", ctx_r2.uploadMeta.revision);
    \u0275\u0275advance(4);
    \u0275\u0275property("disabled", !ctx_r2.selectedFile() || ctx_r2.uploading());
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" ", ctx_r2.uploading() ? "Uploading..." : "Upload", " ");
  }
}
var ShellComponent = class _ShellComponent {
  auth = inject(AuthService);
  projectService = inject(ProjectService);
  documentService = inject(DocumentService);
  router = inject(Router);
  selectedProject = this.projectService.selected;
  showUpload = signal(false, ...ngDevMode ? [{ debugName: "showUpload" }] : (
    /* istanbul ignore next */
    []
  ));
  uploading = signal(false, ...ngDevMode ? [{ debugName: "uploading" }] : (
    /* istanbul ignore next */
    []
  ));
  selectedFile = signal(null, ...ngDevMode ? [{ debugName: "selectedFile" }] : (
    /* istanbul ignore next */
    []
  ));
  uploadMeta = { documentType: "DRAWING" };
  constructor() {
    effect(() => {
      const p = this.selectedProject();
      if (p)
        this.documentService.loadByProject(p.id).subscribe();
    });
  }
  ngOnInit() {
    this.projectService.load().subscribe();
  }
  selectProject(p) {
    this.projectService.select(p);
  }
  openDocument(doc) {
    if (this.documentService.is3D(doc)) {
      this.router.navigate(["/viewer3d", doc.id]);
    } else {
      this.router.navigate(["/viewer", doc.id]);
    }
  }
  openCompare() {
    this.router.navigate(["/compare"]);
  }
  onFileSelect(e) {
    const f = e.target.files?.[0];
    if (!f)
      return;
    this.selectedFile.set(f);
    this.uploadMeta.name = f.name.replace(/\.[^.]+$/, "");
  }
  onDrop(e) {
    e.preventDefault();
    const f = e.dataTransfer?.files?.[0];
    if (f) {
      this.selectedFile.set(f);
      this.uploadMeta.name = f.name.replace(/\.[^.]+$/, "");
    }
  }
  closeUpload() {
    this.showUpload.set(false);
    this.selectedFile.set(null);
    this.uploadMeta = { documentType: "DRAWING" };
  }
  doUpload() {
    const file = this.selectedFile();
    const pid = this.selectedProject()?.id;
    if (!file || !pid)
      return;
    this.uploading.set(true);
    this.documentService.upload(pid, file, this.uploadMeta).subscribe({
      next: () => {
        this.uploading.set(false);
        this.closeUpload();
      },
      error: () => this.uploading.set(false)
    });
  }
  phaseStyle(phase) {
    const map = {
      DESIGN: "background:#dbeafe;color:#1d4ed8",
      CONSTRUCTION: "background:#fef3c7;color:#b45309",
      CONCEPT: "background:#ede9fe;color:#6d28d9",
      HANDOVER: "background:#dcfce7;color:#15803d",
      OPERATION: "background:#f1f5f9;color:#475569"
    };
    return map[phase] || "background:#f1f5f9;color:#475569";
  }
  statusStyle(status) {
    const map = {
      DRAFT: "background:#f1f5f9;color:#64748b",
      IN_REVIEW: "background:#fef3c7;color:#b45309",
      APPROVED: "background:#dcfce7;color:#15803d",
      SUPERSEDED: "background:#fee2e2;color:#b91c1c"
    };
    return map[status] || "background:#f1f5f9;color:#64748b";
  }
  static \u0275fac = function ShellComponent_Factory(__ngFactoryType__) {
    return new (__ngFactoryType__ || _ShellComponent)();
  };
  static \u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _ShellComponent, selectors: [["app-shell"]], decls: 35, vars: 6, consts: [["fileInput", ""], [1, "flex", "flex-col", "h-screen", "overflow-hidden"], [1, "flex", "items-center", "h-11", "px-4", "gap-3", "flex-shrink-0", 2, "background", "var(--nav)", "box-shadow", "0 2px 4px rgba(0,0,0,.15)"], [1, "flex", "items-center", "gap-2"], [1, "w-7", "h-7", "bg-white", "rounded", "flex", "items-center", "justify-center", "text-accent", "font-black", "text-xs"], [1, "text-white", "font-bold", "text-sm", "tracking-wide"], [1, "flex-1"], [1, "w-7", "h-7", "rounded-full", "bg-blue-400", "flex", "items-center", "justify-center", "text-white", "font-bold", "text-xs", "border-2", "border-white/30"], [1, "text-white/85", "text-xs"], [1, "text-xs", "px-3", "py-1", "rounded", "border", "border-white/30", "bg-white/10", "text-white/90", "hover:bg-white/20", "transition-colors", 3, "click"], [1, "flex", "flex-1", "overflow-hidden"], [1, "w-52", "bg-white", "border-r", "border-gray-200", "flex", "flex-col", "flex-shrink-0", "shadow-sm"], [1, "p-3", "border-b", "border-gray-200"], [1, "text-xs", "font-semibold", "uppercase", "tracking-wider", "text-gray-400"], [1, "flex-1", "overflow-y-auto", "p-2"], [1, "px-3", "py-2", "rounded", "cursor-pointer", "mb-0.5", "transition-all", "text-sm", 3, "class"], [1, "flex-1", "flex", "flex-col", "overflow-hidden", "bg-gray-50"], [1, "flex", "items-center", "h-11", "px-5", "border-b", "border-gray-200", "bg-white", "flex-shrink-0"], [1, "text-sm", "font-semibold", "text-gray-800"], [1, "flex-1", "overflow-y-auto", "p-5"], [1, "flex", "flex-col", "items-center", "justify-center", "h-full", "text-gray-400"], [1, "flex", "items-center", "justify-center", "h-32", "text-gray-400", "text-sm"], [1, "flex", "flex-col", "items-center", "justify-center", "h-48", "text-gray-400"], [1, "grid", "gap-3", 2, "grid-template-columns", "repeat(auto-fill,minmax(180px,1fr))"], [1, "fixed", "inset-0", "bg-black/60", "backdrop-blur-sm", "z-50", "flex", "items-center", "justify-center"], [1, "px-3", "py-2", "rounded", "cursor-pointer", "mb-0.5", "transition-all", "text-sm", 3, "click"], [1, "font-medium", "truncate"], [1, "flex", "items-center", "gap-1.5", "mt-0.5"], [1, "text-xs", "px-1.5", "py-0.5", "rounded", "font-semibold"], [1, "text-xs", "text-gray-400"], [1, "flex", "items-center", "gap-1.5", "px-3", "py-1.5", "text-xs", "font-medium", "border", "border-gray-300", "rounded", "bg-white", "hover:bg-gray-50", "text-gray-600", "transition-colors", 3, "click"], [1, "flex", "items-center", "gap-1.5", "px-3", "py-1.5", "text-xs", "font-semibold", "bg-accent", "hover:bg-blue-700", "text-white", "rounded", "transition-colors", 3, "click"], [1, "text-5xl", "mb-3"], [1, "text-sm"], [1, "text-4xl", "mb-3"], [1, "bg-white", "rounded", "border", "border-gray-200", "shadow-sm", "cursor-pointer", "hover:border-accent", "hover:-translate-y-0.5", "hover:shadow-md", "transition-all", "overflow-hidden"], [1, "bg-white", "rounded", "border", "border-gray-200", "shadow-sm", "cursor-pointer", "hover:border-accent", "hover:-translate-y-0.5", "hover:shadow-md", "transition-all", "overflow-hidden", 3, "click"], [1, "h-24", "bg-blue-50", "border-b", "border-gray-200", "flex", "items-center", "justify-center", "text-3xl"], [1, "p-2.5"], [1, "text-xs", "font-semibold", "text-gray-800", "truncate"], [1, "text-xs", "text-gray-500", "mt-0.5", "truncate"], [1, "flex", "items-center", "justify-between", "mt-1.5"], [1, "bg-white", "rounded-lg", "shadow-2xl", "p-7", "w-96"], [1, "font-semibold", "text-gray-800", "mb-5"], [1, "border-2", "border-dashed", "border-gray-300", "rounded-md", "p-6", "text-center", "text-gray-500", "text-sm", "cursor-pointer", "hover:border-accent", "hover:bg-blue-50", "transition-colors", "mb-4", 3, "click", "dragover", "drop"], [1, "text-2xl", "mb-2"], [1, "text-accent", "font-medium"], ["type", "file", "accept", ".pdf,.dxf,.dwg,.ifc,.glb,.gltf,.obj,.stl,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.svg", 1, "hidden", 3, "change"], [1, "space-y-3", "mb-5"], [1, "block", "text-xs", "font-medium", "text-gray-600", "mb-1"], [1, "w-full", "px-3", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", 3, "ngModelChange", "ngModel"], [1, "grid", "grid-cols-2", "gap-2"], [1, "w-full", "px-2", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", 3, "ngModelChange", "ngModel"], ["value", "BIM_MODEL"], ["value", "DRAWING"], ["value", "SPECIFICATION"], ["value", "REPORT"], ["value", "SCHEDULE"], ["value", "OTHER"], ["placeholder", "A", 1, "w-full", "px-3", "py-2", "border", "border-gray-300", "rounded", "text-sm", "focus:outline-none", "focus:ring-2", "focus:ring-accent", 3, "ngModelChange", "ngModel"], [1, "flex", "gap-2", "justify-end"], [1, "px-4", "py-2", "text-sm", "border", "border-gray-300", "rounded", "hover:bg-gray-50", 3, "click"], [1, "px-4", "py-2", "text-sm", "bg-accent", "text-white", "rounded", "hover:bg-blue-700", "disabled:opacity-50", "font-semibold", 3, "click", "disabled"]], template: function ShellComponent_Template(rf, ctx) {
    if (rf & 1) {
      \u0275\u0275elementStart(0, "div", 1)(1, "header", 2)(2, "div", 3)(3, "div", 4);
      \u0275\u0275text(4, "CDE");
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(5, "span", 5);
      \u0275\u0275text(6, "Platform");
      \u0275\u0275elementEnd()();
      \u0275\u0275element(7, "div", 6);
      \u0275\u0275elementStart(8, "div", 3)(9, "div", 7);
      \u0275\u0275text(10);
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(11, "span", 8);
      \u0275\u0275text(12);
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(13, "button", 9);
      \u0275\u0275listener("click", function ShellComponent_Template_button_click_13_listener() {
        return ctx.auth.logout();
      });
      \u0275\u0275text(14, " Sign Out ");
      \u0275\u0275elementEnd()()();
      \u0275\u0275elementStart(15, "div", 10)(16, "aside", 11)(17, "div", 12)(18, "span", 13);
      \u0275\u0275text(19, "Projects");
      \u0275\u0275elementEnd()();
      \u0275\u0275elementStart(20, "div", 14);
      \u0275\u0275repeaterCreate(21, ShellComponent_For_22_Template, 8, 7, "div", 15, _forTrack0);
      \u0275\u0275elementEnd()();
      \u0275\u0275elementStart(23, "main", 16)(24, "div", 17)(25, "h2", 18);
      \u0275\u0275text(26);
      \u0275\u0275elementEnd();
      \u0275\u0275element(27, "div", 6);
      \u0275\u0275conditionalCreate(28, ShellComponent_Conditional_28_Template, 5, 0, "div", 3);
      \u0275\u0275elementEnd();
      \u0275\u0275elementStart(29, "div", 19);
      \u0275\u0275conditionalCreate(30, ShellComponent_Conditional_30_Template, 5, 0, "div", 20)(31, ShellComponent_Conditional_31_Template, 2, 0, "div", 21)(32, ShellComponent_Conditional_32_Template, 5, 0, "div", 22)(33, ShellComponent_Conditional_33_Template, 3, 0, "div", 23);
      \u0275\u0275elementEnd()()()();
      \u0275\u0275conditionalCreate(34, ShellComponent_Conditional_34_Template, 42, 6, "div", 24);
    }
    if (rf & 2) {
      let tmp_0_0;
      \u0275\u0275advance(10);
      \u0275\u0275textInterpolate1(" ", (tmp_0_0 = ctx.auth.username()) == null ? null : (tmp_0_0 = tmp_0_0.charAt(0)) == null ? null : tmp_0_0.toUpperCase(), " ");
      \u0275\u0275advance(2);
      \u0275\u0275textInterpolate(ctx.auth.username());
      \u0275\u0275advance(9);
      \u0275\u0275repeater(ctx.projectService.projects());
      \u0275\u0275advance(5);
      \u0275\u0275textInterpolate1(" ", ctx.selectedProject() ? ctx.selectedProject().name : "Select a project", " ");
      \u0275\u0275advance(2);
      \u0275\u0275conditional(ctx.selectedProject() ? 28 : -1);
      \u0275\u0275advance(2);
      \u0275\u0275conditional(!ctx.selectedProject() ? 30 : ctx.documentService.loading() ? 31 : ctx.documentService.documents().length === 0 ? 32 : 33);
      \u0275\u0275advance(4);
      \u0275\u0275conditional(ctx.showUpload() ? 34 : -1);
    }
  }, dependencies: [CommonModule, FormsModule, NgSelectOption, \u0275NgSelectMultipleOption, DefaultValueAccessor, SelectControlValueAccessor, NgControlStatus, NgModel], encapsulation: 2 });
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && setClassMetadata(ShellComponent, [{
    type: Component,
    args: [{
      selector: "app-shell",
      standalone: true,
      imports: [CommonModule, FormsModule, RouterOutlet],
      template: `
    <div class="flex flex-col h-screen overflow-hidden">

      <!-- \u2500\u2500 Top Navigation Bar \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 -->
      <header class="flex items-center h-11 px-4 gap-3 flex-shrink-0"
              style="background:var(--nav);box-shadow:0 2px 4px rgba(0,0,0,.15)">
        <div class="flex items-center gap-2">
          <div class="w-7 h-7 bg-white rounded flex items-center justify-center text-accent font-black text-xs">CDE</div>
          <span class="text-white font-bold text-sm tracking-wide">Platform</span>
        </div>
        <div class="flex-1"></div>
        <div class="flex items-center gap-2">
          <div class="w-7 h-7 rounded-full bg-blue-400 flex items-center justify-center text-white font-bold text-xs border-2 border-white/30">
            {{ auth.username()?.charAt(0)?.toUpperCase() }}
          </div>
          <span class="text-white/85 text-xs">{{ auth.username() }}</span>
          <button (click)="auth.logout()"
            class="text-xs px-3 py-1 rounded border border-white/30 bg-white/10 text-white/90 hover:bg-white/20 transition-colors">
            Sign Out
          </button>
        </div>
      </header>

      <!-- \u2500\u2500 Body \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 -->
      <div class="flex flex-1 overflow-hidden">

        <!-- Sidebar -->
        <aside class="w-52 bg-white border-r border-gray-200 flex flex-col flex-shrink-0 shadow-sm">
          <div class="p-3 border-b border-gray-200">
            <span class="text-xs font-semibold uppercase tracking-wider text-gray-400">Projects</span>
          </div>
          <div class="flex-1 overflow-y-auto p-2">
            @for (p of projectService.projects(); track p.id) {
              <div (click)="selectProject(p)"
                class="px-3 py-2 rounded cursor-pointer mb-0.5 transition-all text-sm"
                [class]="selectedProject()?.id === p.id
                  ? 'bg-blue-50 border border-blue-200 text-accent'
                  : 'hover:bg-gray-50 text-gray-700'">
                <div class="font-medium truncate">{{ p.name }}</div>
                <div class="flex items-center gap-1.5 mt-0.5">
                  <span class="text-xs px-1.5 py-0.5 rounded font-semibold"
                    [style]="phaseStyle(p.phase)">{{ p.phase }}</span>
                  <span class="text-xs text-gray-400">{{ p.documentCount || 0 }} docs</span>
                </div>
              </div>
            }
          </div>
        </aside>

        <!-- Main content -->
        <main class="flex-1 flex flex-col overflow-hidden bg-gray-50">

          <!-- Content header -->
          <div class="flex items-center h-11 px-5 border-b border-gray-200 bg-white flex-shrink-0">
            <h2 class="text-sm font-semibold text-gray-800">
              {{ selectedProject() ? selectedProject()!.name : 'Select a project' }}
            </h2>
            <div class="flex-1"></div>
            @if (selectedProject()) {
              <div class="flex items-center gap-2">
                <button (click)="openCompare()"
                  class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium border border-gray-300 rounded bg-white hover:bg-gray-50 text-gray-600 transition-colors">
                  \u{1F50D} Compare
                </button>
                <button (click)="showUpload.set(true)"
                  class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold bg-accent hover:bg-blue-700 text-white rounded transition-colors">
                  \u{1F4E4} Upload
                </button>
              </div>
            }
          </div>

          <!-- Document grid -->
          <div class="flex-1 overflow-y-auto p-5">
            @if (!selectedProject()) {
              <div class="flex flex-col items-center justify-center h-full text-gray-400">
                <div class="text-5xl mb-3">\u{1F4C1}</div>
                <div class="text-sm">Select a project to see its documents</div>
              </div>
            } @else if (documentService.loading()) {
              <div class="flex items-center justify-center h-32 text-gray-400 text-sm">Loading...</div>
            } @else if (documentService.documents().length === 0) {
              <div class="flex flex-col items-center justify-center h-48 text-gray-400">
                <div class="text-4xl mb-3">\u{1F4C4}</div>
                <div class="text-sm">No documents yet. Click Upload to add files.</div>
              </div>
            } @else {
              <div class="grid gap-3" style="grid-template-columns:repeat(auto-fill,minmax(180px,1fr))">
                @for (doc of documentService.documents(); track doc.id) {
                  <div (click)="openDocument(doc)"
                    class="bg-white rounded border border-gray-200 shadow-sm cursor-pointer hover:border-accent hover:-translate-y-0.5 hover:shadow-md transition-all overflow-hidden">
                    <div class="h-24 bg-blue-50 border-b border-gray-200 flex items-center justify-center text-3xl">
                      {{ documentService.getFileIcon(doc) }}
                    </div>
                    <div class="p-2.5">
                      <div class="text-xs font-semibold text-gray-800 truncate">{{ doc.name }}</div>
                      <div class="text-xs text-gray-500 mt-0.5 truncate">
                        {{ doc.drawingNumber || doc.documentType }}{{ doc.revision ? ' \xB7 Rev ' + doc.revision : '' }}
                      </div>
                      <div class="flex items-center justify-between mt-1.5">
                        <span class="text-xs px-1.5 py-0.5 rounded font-semibold"
                          [style]="statusStyle(doc.status)">{{ doc.status.replace('_',' ') }}</span>
                      </div>
                    </div>
                  </div>
                }
              </div>
            }
          </div>
        </main>
      </div>
    </div>

    <!-- \u2500\u2500 Upload Modal \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 -->
    @if (showUpload()) {
      <div class="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center">
        <div class="bg-white rounded-lg shadow-2xl p-7 w-96">
          <h3 class="font-semibold text-gray-800 mb-5">\u{1F4C2} Upload Document</h3>

          <!-- Drop zone -->
          <div (click)="fileInput.click()" (dragover)="$event.preventDefault()"
               (drop)="onDrop($event)"
               class="border-2 border-dashed border-gray-300 rounded-md p-6 text-center text-gray-500 text-sm cursor-pointer hover:border-accent hover:bg-blue-50 transition-colors mb-4">
            <div class="text-2xl mb-2">\u{1F4C4}</div>
            @if (selectedFile()) {
              <div class="text-accent font-medium">\u{1F4CE} {{ selectedFile()!.name }}</div>
            } @else {
              Click to browse or drag & drop
            }
          </div>
          <input #fileInput type="file" class="hidden"
            accept=".pdf,.dxf,.dwg,.ifc,.glb,.gltf,.obj,.stl,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.svg"
            (change)="onFileSelect($event)" />

          <div class="space-y-3 mb-5">
            <div>
              <label class="block text-xs font-medium text-gray-600 mb-1">Document Name *</label>
              <input [(ngModel)]="uploadMeta.name" class="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent" />
            </div>
            <div class="grid grid-cols-2 gap-2">
              <div>
                <label class="block text-xs font-medium text-gray-600 mb-1">Type</label>
                <select [(ngModel)]="uploadMeta.documentType"
                  class="w-full px-2 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent">
                  <option value="BIM_MODEL">BIM Model</option>
                  <option value="DRAWING">Drawing</option>
                  <option value="SPECIFICATION">Specification</option>
                  <option value="REPORT">Report</option>
                  <option value="SCHEDULE">Schedule</option>
                  <option value="OTHER">Other</option>
                </select>
              </div>
              <div>
                <label class="block text-xs font-medium text-gray-600 mb-1">Revision</label>
                <input [(ngModel)]="uploadMeta.revision" placeholder="A"
                  class="w-full px-3 py-2 border border-gray-300 rounded text-sm focus:outline-none focus:ring-2 focus:ring-accent" />
              </div>
            </div>
          </div>

          <div class="flex gap-2 justify-end">
            <button (click)="closeUpload()"
              class="px-4 py-2 text-sm border border-gray-300 rounded hover:bg-gray-50">Cancel</button>
            <button (click)="doUpload()" [disabled]="!selectedFile() || uploading()"
              class="px-4 py-2 text-sm bg-accent text-white rounded hover:bg-blue-700 disabled:opacity-50 font-semibold">
              {{ uploading() ? 'Uploading...' : 'Upload' }}
            </button>
          </div>
        </div>
      </div>
    }
  `
    }]
  }], () => [], null);
})();
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(ShellComponent, { className: "ShellComponent", filePath: "src/app/features/projects/shell.component.ts", lineNumber: 187 });
})();
export {
  ShellComponent
};
//# sourceMappingURL=chunk-JC55P7LT.js.map
